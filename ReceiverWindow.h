#import <CoreImage/CoreImage.h>
#include <signal.h>
#include <dns_sd.h>
#include <arpa/inet.h>
#include <ifaddrs.h>
#include <net/if.h>

static NSDictionary<NSString *, NSString *> *PrivateEndpoints(void) {
    NSMutableDictionary *endpoints = [NSMutableDictionary dictionary];
    struct ifaddrs *interfaces = NULL;
    if (getifaddrs(&interfaces) != 0) return endpoints;
    for (struct ifaddrs *item = interfaces; item; item = item->ifa_next) {
        const struct sockaddr *address = item->ifa_addr;
        if (!address || !(item->ifa_flags & IFF_UP) || (item->ifa_flags & IFF_LOOPBACK)) continue;
        const void *bytes;
        if (address->sa_family == AF_INET) {
            const struct in_addr *ip = &((const struct sockaddr_in *)address)->sin_addr;
            uint32_t value = ntohl(ip->s_addr);
            if (value >> 24 != 10 && value >> 20 != 0xac1 && value >> 16 != 0xc0a8) continue;
            bytes = ip;
        } else if (address->sa_family == AF_INET6) {
            const struct in6_addr *ip = &((const struct sockaddr_in6 *)address)->sin6_addr;
            if ((ip->s6_addr[0] & 0xfe) != 0xfc) continue;
            bytes = ip;
        } else {
            continue;
        }
        char literal[INET6_ADDRSTRLEN];
        if (inet_ntop(address->sa_family, bytes, literal, sizeof literal)) {
            NSString *host = @(literal);
            endpoints[[NSString stringWithFormat:@"%s — %@", item->ifa_name, host]] = host;
        }
    }
    freeifaddrs(interfaces);
    return endpoints;
}

static NSImage *InvitationImage(NSString *url) {
    CIFilter *filter = [CIFilter filterWithName:@"CIQRCodeGenerator"];
    [filter setValue:[url dataUsingEncoding:NSUTF8StringEncoding] forKey:@"inputMessage"];
    CIImage *code = [filter.outputImage imageByApplyingTransform:CGAffineTransformMakeScale(4, 4)];
    if (!code) return nil;
    // The white quiet zone keeps the code readable in dark mode.
    CGRect extent = CGRectInset(code.extent, -20, -20);
    CIImage *white = [[CIImage imageWithColor:CIColor.whiteColor] imageByCroppingToRect:extent];
    NSCIImageRep *rep = [NSCIImageRep imageRepWithCIImage:[code imageByCompositingOverImage:white]];
    NSImage *image = [[NSImage alloc] initWithSize:rep.size];
    [image addRepresentation:rep];
    return image;
}

@interface ReceiverWindow : NSObject <NSApplicationDelegate>
- (void)bonjourResult:(DNSServiceErrorType)error;
@end

static void BonjourRegistered(DNSServiceRef ref, DNSServiceFlags flags, DNSServiceErrorType error,
                              const char *name, const char *type, const char *domain, void *context) {
    (void)ref; (void)flags; (void)name; (void)type; (void)domain;
    [(__bridge ReceiverWindow *)context bonjourResult:error];
}

@implementation ReceiverWindow {
    NSWindow *_window;
    NSTextField *_status, *_discovery;
    NSImageView *_qr;
    NSButton *_pair, *_approve, *_disconnect, *_forget, *_live;
    NSTask *_task;
    NSFileHandle *_commands;
    DNSServiceRef _service;
    NSString *_request;
    BOOL _listening, _paired;
}

- (void)updateControls {
    _pair.enabled = _listening && !_paired && !_request && !_qr.image;
    _approve.enabled = _listening && _request != nil;
    _forget.enabled = _listening && _paired;
    _disconnect.enabled = _listening;
    _live.enabled = _listening;
}

- (void)send:(NSString *)command host:(NSString *)host {
    if (!_task.running) return;
    NSMutableDictionary *message = [@{@"command": command} mutableCopy];
    if (host) message[@"host"] = host;
    if (_request) message[@"request"] = _request;
    NSMutableData *data = [[NSJSONSerialization dataWithJSONObject:message options:0 error:nil] mutableCopy];
    [data appendBytes:"\n" length:1];
    if (![_commands writeData:data error:nil]) [self failed:@"Receiver stopped. Reopen Pixel Trackpad."];
}

- (void)send:(NSString *)command { [self send:command host:nil]; }

- (void)pair:(id)sender {
    (void)sender;
    NSDictionary *endpoints = PrivateEndpoints();
    NSArray *labels = [[endpoints allKeys] sortedArrayUsingSelector:@selector(localizedStandardCompare:)];
    if (labels.count == 0) {
        [self send:@"pair"];
    } else if (labels.count == 1) {
        [self send:@"pair" host:endpoints[labels.firstObject]];
    } else {
        NSAlert *alert = [NSAlert new];
        alert.messageText = @"Choose the Mac interface on your phone’s network";
        NSPopUpButton *choices = [[NSPopUpButton alloc] initWithFrame:NSMakeRect(0, 0, 340, 28) pullsDown:NO];
        [choices addItemsWithTitles:labels];
        [choices setAccessibilityLabel:@"Private LAN address"];
        alert.accessoryView = choices;
        [alert addButtonWithTitle:@"Show QR"];
        [alert addButtonWithTitle:@"Cancel"];
        [alert beginSheetModalForWindow:_window completionHandler:^(NSModalResponse result) {
            if (result == NSAlertFirstButtonReturn) [self send:@"pair" host:endpoints[choices.titleOfSelectedItem]];
        }];
    }
}
- (void)approve:(id)sender { (void)sender; [self send:@"approve"]; _approve.enabled = NO; }
- (void)disconnect:(id)sender { (void)sender; [self send:@"disconnect"]; }
- (void)forget:(id)sender {
    (void)sender;
    NSAlert *alert = [NSAlert new];
    alert.messageText = @"Forget the paired phone?";
    alert.informativeText = @"The phone will need a new invitation before it can reconnect.";
    [alert addButtonWithTitle:@"Forget Phone"];
    [alert addButtonWithTitle:@"Cancel"];
    [alert beginSheetModalForWindow:_window completionHandler:^(NSModalResponse result) {
        if (result == NSAlertFirstButtonReturn) [self send:@"forget"];
    }];
}

- (void)mode:(NSButton *)sender {
    if (sender.state == NSControlStateValueOn && !AXIsProcessTrustedWithOptions((__bridge CFDictionaryRef)@{(__bridge NSString *)kAXTrustedCheckOptionPrompt: @YES})) {
        sender.state = NSControlStateValueOff;
        _status.stringValue = @"Allow Pixel Trackpad in macOS Accessibility settings, then enable this checkbox again. Dry run remains active.";
        return;
    }
    [self send:sender.state == NSControlStateValueOn ? @"live" : @"dry"];
}

- (void)idle {
    _qr.image = nil;
    _request = nil;
    NSString *state = _paired ? @"Phone remembered. Select this Mac on your phone to connect." : @"Ready to pair. Use Pair Phone to show an invitation.";
    NSString *mode = _live.state == NSControlStateValueOn ? @"Live input enabled." : @"Dry run. No input is posted.";
    _status.stringValue = [NSString stringWithFormat:@"%@\n%@", state, mode];
    [self updateControls];
}

- (void)stopBonjour {
    if (_service) {
        DNSServiceRefDeallocate(_service);
        _service = NULL;
    }
}

- (void)failed:(NSString *)message {
    [self stopBonjour];
    [_commands closeFile];
    _commands = nil;
    _listening = NO;
    _discovery.stringValue = @"Bonjour inactive.";
    _qr.image = nil;
    _request = nil;
    _status.stringValue = message;
    [self updateControls];
}

- (void)bonjourResult:(DNSServiceErrorType)error {
    _discovery.stringValue = error ? @"Bonjour unavailable. Direct pairing needs a private LAN address."
                                   : @"Bonjour advertised. Direct pairing needs a private LAN address.";
    if (error) [self stopBonjour];
}

- (void)receive:(NSDictionary *)message {
    NSString *event = message[@"event"];
    if ([event isEqual:@"listening"]) {
        _listening = YES;
        _paired = [message[@"paired"] boolValue];
        [self idle];
        _discovery.stringValue = @"Bonjour registration pending.";
        DNSServiceErrorType error = DNSServiceRegister(&_service, kDNSServiceFlagsNoAutoRename, 0,
                                                      [message[@"service"] UTF8String], "_pixeltrackpad._tcp",
                                                      "local.", NULL, htons([message[@"port"] unsignedShortValue]),
                                                      0, NULL, BonjourRegistered, (__bridge void *)self);
        if (!error) error = DNSServiceSetDispatchQueue(_service, dispatch_get_main_queue());
        if (error) [self bonjourResult:error];
        __weak ReceiverWindow *weakSelf = self;
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 6 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
            ReceiverWindow *owner = weakSelf;
            if (owner && [owner->_discovery.stringValue isEqual:@"Bonjour registration pending."])
                owner->_discovery.stringValue = @"Bonjour unconfirmed. Direct pairing needs a private LAN address.";
        });
    } else if ([event isEqual:@"invitation"]) {
        _qr.image = InvitationImage(message[@"url"]);
        if (!_qr.image) { [self send:@"disconnect"]; return; }
        _status.stringValue = [message[@"url"] hasPrefix:@"local.pixeltrackpad://pair/v1/"]
            ? @"Tap Scan QR in Pixel Trackpad on your phone. No private LAN address; Bonjour discovery is required. Invitation expires in two minutes."
            : @"Open Pixel Trackpad on your phone and tap Scan QR. Invitation expires in two minutes.";
        [self updateControls];
    } else if ([event isEqual:@"approval"]) {
        _qr.image = nil;
        _request = message[@"request"];
        _status.stringValue = [NSString stringWithFormat:@"Approve only if the phone shows code %@. Approval expires within 30 seconds.", message[@"code"]];
        [self updateControls];
    } else if ([event isEqual:@"connected"]) {
        [self idle];
        _status.stringValue = _live.state == NSControlStateValueOn ? @"Phone connected. Live input enabled." : @"Phone connected. Dry run.";
    } else if ([event isEqual:@"idle"]) {
        _paired = [message[@"paired"] boolValue];
        [self idle];
    } else if ([event isEqual:@"error"]) {
        [self failed:@"Receiver could not start. Check Python, local network access and private receiver storage."];
    }
}

- (void)applicationDidFinishLaunching:(NSNotification *)notification {
    (void)notification;
    signal(SIGPIPE, SIG_IGN);
    _window = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, 520, 475)
                                          styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskMiniaturizable
                                            backing:NSBackingStoreBuffered defer:NO];
    _window.title = @"Pixel Trackpad";
    _window.releasedWhenClosed = NO;
    NSStackView *column = [NSStackView stackViewWithViews:@[]];
    column.orientation = NSUserInterfaceLayoutOrientationVertical;
    column.alignment = NSLayoutAttributeCenterX;
    column.spacing = 12;
    column.edgeInsets = NSEdgeInsetsMake(20, 20, 20, 20);
    _status = [NSTextField wrappingLabelWithString:@"Starting receiver…"];
    [_status.widthAnchor constraintEqualToConstant:470].active = YES;
    [column addArrangedSubview:_status];
    _discovery = [NSTextField wrappingLabelWithString:@"Bonjour inactive."];
    [_discovery.widthAnchor constraintEqualToConstant:470].active = YES;
    [column addArrangedSubview:_discovery];
    _pair = [NSButton buttonWithTitle:@"Pair Phone" target:self action:@selector(pair:)];
    _approve = [NSButton buttonWithTitle:@"Approve" target:self action:@selector(approve:)];
    _disconnect = [NSButton buttonWithTitle:@"Disconnect / Cancel" target:self action:@selector(disconnect:)];
    _forget = [NSButton buttonWithTitle:@"Forget Phone" target:self action:@selector(forget:)];
    [column addArrangedSubview:[NSStackView stackViewWithViews:@[_pair, _approve, _disconnect, _forget]]];
    _live = [NSButton checkboxWithTitle:@"Enable live input for this launch" target:self action:@selector(mode:)];
    [column addArrangedSubview:_live];
    _qr = [NSImageView new];
    [_qr setAccessibilityLabel:@"Single-use phone pairing QR code"];
    _qr.imageScaling = NSImageScaleProportionallyDown;
    [_qr.widthAnchor constraintEqualToConstant:275].active = YES;
    [_qr.heightAnchor constraintEqualToConstant:275].active = YES;
    [column addArrangedSubview:_qr];
    _window.contentView = column;
    [self updateControls];
    [_window center];
    [_window makeKeyAndOrderFront:nil];
    [NSApp activateIgnoringOtherApps:YES];

    NSBundle *bundle = NSBundle.mainBundle;
    NSURL *devPath = [bundle URLForResource:@"python-path" withExtension:nil];
    NSString *python = devPath ? [NSString stringWithContentsOfURL:devPath encoding:NSUTF8StringEncoding error:nil]
                               : [bundle.resourcePath stringByAppendingPathComponent:@"runtime/bin/python3.13"];
    if (!python.length || ![NSFileManager.defaultManager isExecutableFileAtPath:python]) { [self failed:@"Python path is missing. Rebuild Pixel Trackpad."]; return; }
    NSPipe *input = [NSPipe pipe], *output = [NSPipe pipe];
    _commands = input.fileHandleForWriting;
    _task = [NSTask new];
    _task.executableURL = [NSURL fileURLWithPath:python];
    NSString *state = [NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, YES).firstObject
                       stringByAppendingPathComponent:@"Pixel Trackpad Receiver"];
    _task.arguments = @[@"-I", @"-B", @"-u", @"-c",
                        @"import runpy,sys; sys.path.insert(0,sys.argv.pop(1)); runpy.run_module('onboarding',run_name='__main__')",
                        bundle.resourcePath, state, bundle.executablePath];
    _task.standardInput = input;
    _task.standardOutput = output;
    _task.standardError = NSFileHandle.fileHandleWithNullDevice;
    __block NSMutableData *buffer = [NSMutableData data];
    __weak ReceiverWindow *weakSelf = self;
    output.fileHandleForReading.readabilityHandler = ^(NSFileHandle *handle) {
        NSData *chunk = handle.availableData;
        if (!chunk.length) {
            handle.readabilityHandler = nil;
            dispatch_async(dispatch_get_main_queue(), ^{
                ReceiverWindow *owner = weakSelf;
                if (owner && owner->_commands) [owner failed:@"Receiver stopped. Check Python and local network access."];
            });
            return;
        }
        [buffer appendData:chunk];
        for (;;) {
            NSRange end = [buffer rangeOfData:[NSData dataWithBytes:"\n" length:1] options:0 range:NSMakeRange(0, buffer.length)];
            if (end.location == NSNotFound) break;
            NSData *line = [buffer subdataWithRange:NSMakeRange(0, end.location)];
            [buffer replaceBytesInRange:NSMakeRange(0, end.location+1) withBytes:NULL length:0];
            NSDictionary *message = [NSJSONSerialization JSONObjectWithData:line options:0 error:nil];
            if ([message isKindOfClass:NSDictionary.class])
                dispatch_async(dispatch_get_main_queue(), ^{ [weakSelf receive:message]; });
        }
    };
    if (![_task launchAndReturnError:nil]) [self failed:@"Python could not launch. Check the app build."];
}

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)sender { (void)sender; return YES; }
- (void)applicationWillTerminate:(NSNotification *)notification { (void)notification; [_commands closeFile]; [self stopBonjour]; }
@end
