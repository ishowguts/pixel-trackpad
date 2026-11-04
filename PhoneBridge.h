// Included after the diagnostic factory so both entry points use identical recipes.
#import <poll.h>
#import <signal.h>
#import <unistd.h>

static volatile sig_atomic_t BridgeStopped;
static void BridgeSignal(int signalNumber) { (void)signalNumber; BridgeStopped = 1; }

static CGPoint BridgeClamp(CGPoint point) {
    CGDirectDisplayID displays[32];
    uint32_t count = 0;
    CGGetActiveDisplayList(32, displays, &count);
    CGPoint closest = point;
    double distance = INFINITY;
    for (uint32_t i = 0; i < count; i++) {
        CGRect bounds = CGDisplayBounds(displays[i]);
        CGPoint candidate = CGPointMake(fmax(CGRectGetMinX(bounds), fmin(point.x, CGRectGetMaxX(bounds) - 1)),
                                        fmax(CGRectGetMinY(bounds), fmin(point.y, CGRectGetMaxY(bounds) - 1)));
        double d = hypot(candidate.x - point.x, candidate.y - point.y);
        if (d < distance) { distance = d; closest = candidate; }
    }
    return closest;
}

static BOOL BridgeNumber(NSDictionary *message, NSString *key, double limit) {
    id value = message[key];
    return [value isKindOfClass:NSNumber.class] && CFGetTypeID((__bridge CFTypeRef)value) != CFBooleanGetTypeID()
        && isfinite([value doubleValue]) && fabs([value doubleValue]) <= limit;
}

static BOOL BridgeInteger(NSDictionary *message, NSString *key, int low, int high) {
    double value = [message[key] respondsToSelector:@selector(doubleValue)] ? [message[key] doubleValue] : NAN;
    return BridgeNumber(message, key, high) && value >= low && value == trunc(value);
}

static int PhoneBridge(BOOL dry) {
    if (!dry && !AXIsProcessTrusted()) {
        fputs("Accessibility required for this GestureDiagnostic executable. Authorize its app bundle before live use.\n", stderr);
        return 1;
    }
    signal(SIGINT, BridgeSignal); signal(SIGTERM, BridgeSignal);
    signal(SIGPIPE, SIG_IGN); // A dead Python reader must not bypass held-input cleanup.
    CGEventSourceRef source = CGEventSourceCreate(kCGEventSourceStateCombinedSessionState);
    if (!source) return 1;
    CGEventRef pointer = CGEventCreate(NULL);
    CGPoint location = pointer ? CGEventGetLocation(pointer) : CGPointZero;
    if (pointer) CFRelease(pointer);
    BOOL held = NO, valid = YES;
    Frame active = {0};
    // one synchronous stream, one gesture at a time; multiplex only if combined gestures are required.
    char buffer[2048]; size_t length = 0;
    while (!BridgeStopped) {
        struct pollfd input = {STDIN_FILENO, POLLIN, 0};
        int ready = poll(&input, 1, 2000);
        if (ready <= 0) break;
        char byte;
        if (read(STDIN_FILENO, &byte, 1) != 1) break;
        if (byte != '\n') {
            if (length == sizeof(buffer)) { valid = NO; break; }
            buffer[length++] = byte; continue;
        }
        @autoreleasepool {
            NSData *data = [NSData dataWithBytes:buffer length:length]; length = 0;
            id object = [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL];
            if (![object isKindOfClass:NSDictionary.class]) { valid = NO; break; }
            NSDictionary *message = object;
            NSString *op = message[@"op"];
            if (![op isKindOfClass:NSString.class]) { valid = NO; break; }
            CGEventRef event = NULL;
            if ([op isEqual:@"ping"]) {
                // Keeps the watchdog alive while the authenticated phone is idle.
            } else if ([op isEqual:@"anchor"]) {
                CGEventRef current = CGEventCreate(NULL);
                if (current) { location = CGEventGetLocation(current); CFRelease(current); }
            } else if ([op isEqual:@"move"]) {
                if (!BridgeNumber(message, @"x", 1000) || !BridgeNumber(message, @"y", 1000)) { valid = NO; break; }
                location = BridgeClamp(CGPointMake(location.x + [message[@"x"] doubleValue], location.y + [message[@"y"] doubleValue]));
                event = CGEventCreateMouseEvent(source, held ? kCGEventLeftMouseDragged : kCGEventMouseMoved, location, kCGMouseButtonLeft);
            } else if ([op isEqual:@"button"]) {
                if (!BridgeInteger(message, @"down", 0, 1) || !BridgeInteger(message, @"right", 0, 1) || !BridgeInteger(message, @"count", 1, 2)) { valid = NO; break; }
                BOOL right = [message[@"right"] boolValue], down = [message[@"down"] boolValue];
                if (right && down) { valid = NO; break; } // Secondary click is emitted atomically below.
                if (right) {
                    CGEventRef click = CGEventCreateMouseEvent(source, kCGEventRightMouseDown, location, kCGMouseButtonRight);
                    if (!click) { valid = NO; break; }
                    if (!dry) CGEventPost(kCGSessionEventTap, click);
                    CFRelease(click);
                    event = CGEventCreateMouseEvent(source, kCGEventRightMouseUp, location, kCGMouseButtonRight);
                } else {
                    held = down;
                    event = CGEventCreateMouseEvent(source, down ? kCGEventLeftMouseDown : kCGEventLeftMouseUp, location, kCGMouseButtonLeft);
                }
                if (event) CGEventSetIntegerValueField(event, kCGMouseEventClickState, [message[@"count"] intValue]);
            } else if ([op isEqual:@"gesture"]) {
                if (!BridgeInteger(message, @"test", 0, AppExpose) || !BridgeInteger(message, @"phase", 0, 8) ||
                    !BridgeNumber(message, @"value", 1000) || !BridgeNumber(message, @"x", 1000) || !BridgeInteger(message, @"momentum", 0, 1)) { valid = NO; break; }
                int test = [message[@"test"] intValue], phase = [message[@"phase"] intValue];
                if (test < 0 || !(phase == 0 || phase == 1 || phase == 2 || phase == 4 || phase == 8) ||
                    (test >= DesktopCancel && NSProcessInfo.processInfo.operatingSystemVersion.majorVersion >= 27)) { valid = NO; break; }
                Frame frame = {test, phase, [message[@"momentum"] boolValue], [message[@"value"] doubleValue], [message[@"x"] doubleValue]};
                event = CreateEvent(source, location, frame);
                active = ActiveAfter(frame);
            } else { valid = NO; break; }
            if (event) { if (!dry) CGEventPost(kCGSessionEventTap, event); CFRelease(event); }
            else if (![op isEqual:@"ping"] && ![op isEqual:@"anchor"]) { valid = NO; break; }
            if (puts("ok") == EOF || fflush(stdout) == EOF) { valid = NO; break; }
        }
    }
    Frame closing = StopFrame(active);
    if (closing.phase != None) {
        CGEventRef event = CreateEvent(source, location, closing);
        if (event) { if (!dry) CGEventPost(kCGSessionEventTap, event); CFRelease(event); }
    }
    if (held) {
        CGEventRef event = CGEventCreateMouseEvent(source, kCGEventLeftMouseUp, location, kCGMouseButtonLeft);
        if (event) { if (!dry) CGEventPost(kCGSessionEventTap, event); CFRelease(event); }
    }
    CFRelease(source);
    if (dry) fputs("Bridge closed; drag and gesture cleanup completed. No events posted.\n", stderr);
    return valid && length == 0 ? 0 : 2;
}
