#import <AppKit/AppKit.h>
#import <ApplicationServices/ApplicationServices.h>
#import <math.h>
#import <stdio.h>
#import <stdint.h>
#import <string.h>

typedef NS_ENUM(NSInteger, Test) {
    Scroll, Pinch, Rotate, SmartZoom, DesktopCancel, DesktopLeft, DesktopRight,
    MissionCancel, MissionControl, AppExpose, TestCount
};

typedef NS_ENUM(int, Phase) {
    None = 0, Begin = 1, Change = 2, End = 4, Cancel = 8
};

typedef struct {
    Test test;
    Phase phase;
    BOOL momentum;
    double value;
    double horizontalPixels;
} Frame;

static NSString *Title(Test test) {
    return @[@"Two-axis scroll and momentum", @"Pinch magnification", @"Rotation",
             @"Smart zoom", @"Desktop swipe left and reverse",
             @"Desktop swipe left and commit", @"Desktop swipe right and commit",
             @"Mission Control swipe and reverse", @"Mission Control swipe and commit",
             @"App Exposé swipe and commit"][test];
}

static BOOL IsDockSwipe(Test test) {
    return test >= DesktopCancel;
}

static BOOL TargetsCanvas(Test test, BOOL localSelected) {
    return localSelected && !IsDockSwipe(test);
}

static CGPoint QuartzPoint(NSPoint screenPoint, CGRect primaryDisplay) {
    return CGPointMake(screenPoint.x, CGRectGetMaxY(primaryDisplay) - screenPoint.y);
}

// Fixed samples make this a repeatable diagnostic. Calibrate against
// captured trackpad traces if the receiving app needs different pacing or gain.
static NSArray<NSValue *> *Sequence(Test test) {
    NSMutableArray<NSValue *> *frames = [NSMutableArray array];
    void (^append)(Phase, BOOL, double, double) = ^(Phase phase, BOOL momentum, double value, double x) {
        Frame frame = {test, phase, momentum, value, x};
        [frames addObject:[NSValue valueWithBytes:&frame objCType:@encode(Frame)]];
    };

    if (test == SmartZoom) {
        append(None, NO, 0, 0);
        return frames;
    }

    append(Begin, NO, 0, 0);
    if (test == Scroll) {
        for (int i = 0; i < 24; i++) append(Change, NO, -10, -6);
        append(End, NO, 0, 0);
        append(Begin, YES, -9, -5);
        for (int i = 18; i >= 1; i--) append(Change, YES, -((i + 1) / 2), -((i + 3) / 4));
        append(End, YES, 0, 0);
    } else if (test == Pinch || test == Rotate) {
        for (int i = 0; i < 30; i++) append(Change, NO, test == Pinch ? 0.018 : 3, 0);
        append(End, NO, 0, 0);
    } else {
        double direction = (test == DesktopRight || test == AppExpose) ? -1 : 1;
        if (test == DesktopCancel || test == MissionCancel) {
            for (int i = 1; i <= 16; i++) append(Change, NO, direction * i / 40, 0);
            for (int i = 15; i >= 1; i--) append(Change, NO, direction * i / 40, 0);
            append(Cancel, NO, 0, 0);
        } else {
            for (int i = 1; i <= 30; i++) append(Change, NO, direction * i / 30, 0);
            append(End, NO, direction, 0);
        }
    }
    return frames;
}

static Frame ReadFrame(NSValue *value) {
    Frame frame;
    [value getValue:&frame size:sizeof(frame)];
    return frame;
}

// These fields use CoreGraphics accessors. No CGEvent memory layout is assumed.
// The Dock field encoding is adapted from Scott Lamb's macos-trackpad-companion.
// See NOTICE for attribution and IMPLEMENTATION.md for the source references.
enum {
    GestureType = 29, DockControlType = 30,
    EventTypeField = 55, GestureSubtype = 110, Magnification = 113, Rotation = 114,
    SwipeAxis = 123, SwipeProgress = 124, SwipeVelocityX = 129, SwipeVelocityY = 130,
    GesturePhase = 132, SwipeProgressBits = 135,
    RotationSubtype = 5, MagnifySubtype = 8, SmartZoomSubtype = 22, DockSwipeSubtype = 23
};

static CGEventRef CreateEvent(CGEventSourceRef source, CGPoint location, Frame frame) {
    CGEventRef event;
    if (frame.test == Scroll) {
        event = CGEventCreateScrollWheelEvent2(source, kCGScrollEventUnitPixel, 2,
                                               (int32_t)frame.value, (int32_t)frame.horizontalPixels, 0);
        if (!event) return NULL;
        CGEventSetIntegerValueField(event, kCGScrollWheelEventIsContinuous, 1);
        CGEventSetIntegerValueField(event, kCGScrollWheelEventScrollPhase, frame.momentum ? 0 : frame.phase);
        if (frame.momentum) {
            CGMomentumScrollPhase phase = frame.phase == Begin ? kCGMomentumScrollPhaseBegin :
                frame.phase == Change ? kCGMomentumScrollPhaseContinue : kCGMomentumScrollPhaseEnd;
            CGEventSetIntegerValueField(event, kCGScrollWheelEventMomentumPhase, phase);
        }
    } else {
        event = CGEventCreate(source);
        if (!event) return NULL;
        BOOL dock = IsDockSwipe(frame.test);
        CGEventSetIntegerValueField(event, EventTypeField, dock ? DockControlType : GestureType);
        int subtype = dock ? DockSwipeSubtype : frame.test == Pinch ? MagnifySubtype :
            frame.test == Rotate ? RotationSubtype : SmartZoomSubtype;
        CGEventSetIntegerValueField(event, GestureSubtype, subtype);
        if (frame.phase != None) CGEventSetIntegerValueField(event, GesturePhase, frame.phase);
        if (dock) {
            CGEventSetIntegerValueField(event, SwipeAxis, frame.test <= DesktopRight ? 1 : 2);
            CGEventSetDoubleValueField(event, SwipeProgress, frame.value);
            float progress = (float)frame.value;
            int32_t bits;
            memcpy(&bits, &progress, sizeof(bits));
            CGEventSetIntegerValueField(event, SwipeProgressBits, bits);
            if (frame.phase == End || frame.phase == Cancel) {
                CGEventSetDoubleValueField(event, SwipeVelocityX, 0);
                CGEventSetDoubleValueField(event, SwipeVelocityY, 0);
            }
        } else if (frame.test != SmartZoom) {
            CGEventSetDoubleValueField(event, frame.test == Pinch ? Magnification : Rotation, frame.value);
        }
    }
    CGEventSetLocation(event, location);
    return event;
}

// State records the last posted frame only while its phase is open.
static Frame ActiveAfter(Frame frame) {
    return (frame.phase == Begin || frame.phase == Change) ? frame : (Frame){0};
}

static Frame StopFrame(Frame active) {
    if (active.phase == None) return active;
    active.phase = active.momentum ? End : Cancel;
    if (!IsDockSwipe(active.test)) active.value = 0;
    active.horizontalPixels = 0;
    return active;
}

// AppKit responder deliveries and recognizer actions are separate observations.
typedef NS_ENUM(NSUInteger, ReceptionRow) {
    ReceivedScroll, ReceivedMagnify, ReceivedRotate, ReceivedSmartZoom,
    RecognizedMagnify, RecognizedRotate, ReceptionRowCount
};

@interface GestureCanvas : NSView
- (void)reset;
@end

@implementation GestureCanvas {
    NSTextField *_rows[ReceptionRowCount];
    NSUInteger _counts[ReceptionRowCount];
    NSUInteger _phases[ReceptionRowCount];
    NSUInteger _momentumPhases;
    double _lastScrollY;
    double _lastNonzero[ReceptionRowCount];
}

- (instancetype)initWithFrame:(NSRect)frame {
    self = [super initWithFrame:frame];
    if (!self) return nil;
    self.accessibilityElement = YES;
    self.accessibilityRole = NSAccessibilityGroupRole;
    self.accessibilityLabel = @"Local gesture test canvas";
    self.accessibilityHelp = @"Read the rows for AppKit responder and gesture recognizer results.";

    for (ReceptionRow row = 0; row < ReceptionRowCount; row++) {
        NSTextField *label = [NSTextField labelWithString:@""];
        label.frame = NSMakeRect(6, NSHeight(frame) - 19 - 18 * row, NSWidth(frame) - 12, 18);
        [self addSubview:label];
        _rows[row] = label;
    }
    [self addGestureRecognizer:[[NSMagnificationGestureRecognizer alloc] initWithTarget:self
                                                         action:@selector(recognizedMagnify:)]];
    [self addGestureRecognizer:[[NSRotationGestureRecognizer alloc] initWithTarget:self
                                                     action:@selector(recognizedRotate:)]];
    [self reset];
    return self;
}

- (NSView *)hitTest:(NSPoint)point {
    return [super hitTest:point] ? self : nil;
}

- (BOOL)acceptsFirstResponder { return YES; }

- (void)showRow:(ReceptionRow)row detail:(NSString *)detail {
    NSArray<NSString *> *names = @[@"Scroll responder", @"Magnify responder", @"Rotate responder",
                                  @"Smart magnify responder", @"Magnify recognizer", @"Rotate recognizer"];
    _rows[row].stringValue = [NSString stringWithFormat:@"%@: %lu%@", names[row],
                             (unsigned long)_counts[row], detail];
}

- (void)reset {
    memset(_counts, 0, sizeof(_counts));
    memset(_phases, 0, sizeof(_phases));
    memset(_lastNonzero, 0, sizeof(_lastNonzero));
    _momentumPhases = 0;
    _lastScrollY = 0;
    for (ReceptionRow row = 0; row < ReceptionRowCount; row++) [self showRow:row detail:@""];
}

- (void)scrollWheel:(NSEvent *)event {
    _counts[ReceivedScroll]++;
    _phases[ReceivedScroll] |= event.phase;
    _momentumPhases |= event.momentumPhase;
    if (event.scrollingDeltaX || event.scrollingDeltaY) {
        _lastNonzero[ReceivedScroll] = event.scrollingDeltaX;
        _lastScrollY = event.scrollingDeltaY;
    }
    [self showRow:ReceivedScroll detail:[NSString stringWithFormat:
        @"  Δx %.2f  Δy %.2f  phases %#lx  momentum %#lx",
        _lastNonzero[ReceivedScroll], _lastScrollY,
        (unsigned long)_phases[ReceivedScroll], (unsigned long)_momentumPhases]];
}

- (void)record:(ReceptionRow)row value:(double)value phase:(NSUInteger)phase {
    _counts[row]++;
    _phases[row] |= phase;
    if (value != 0) _lastNonzero[row] = value;
    NSString *detail = row == ReceivedSmartZoom ?
        [NSString stringWithFormat:@"  phases %#lx", (unsigned long)_phases[row]] :
        [NSString stringWithFormat:@"  value %.3f  %@ %#lx", _lastNonzero[row],
         row >= RecognizedMagnify ? @"states" : @"phases", (unsigned long)_phases[row]];
    [self showRow:row detail:detail];
}

- (void)magnifyWithEvent:(NSEvent *)event {
    [self record:ReceivedMagnify value:event.magnification phase:event.phase];
}

- (void)rotateWithEvent:(NSEvent *)event {
    [self record:ReceivedRotate value:event.rotation phase:event.phase];
}

- (void)smartMagnifyWithEvent:(NSEvent *)event {
    [self record:ReceivedSmartZoom value:0 phase:event.phase];
}

- (void)recognizedMagnify:(NSMagnificationGestureRecognizer *)recognizer {
    [self record:RecognizedMagnify value:recognizer.magnification phase:1UL << recognizer.state];
}

- (void)recognizedRotate:(NSRotationGestureRecognizer *)recognizer {
    [self record:RecognizedRotate value:recognizer.rotationInDegrees phase:1UL << recognizer.state];
}
@end

@interface Diagnostic : NSObject <NSApplicationDelegate, NSWindowDelegate>
@end

@implementation Diagnostic {
    NSPanel *_panel;
    NSTextField *_status;
    NSTextField *_routing;
    NSString *_routingStart;
    id _scrollMonitor;
    NSUInteger _appScrollCount;
    GestureCanvas *_canvas;
    NSButton *_localTarget;
    BOOL _targetsCanvas;
    NSTimer *_timer;
    NSArray<NSValue *> *_frames;
    NSUInteger _next;
    NSInteger _countdown;
    Test _test;
    Frame _active;
    CGPoint _location;
    CGEventSourceRef _source;
}

- (void)applicationDidFinishLaunching:(NSNotification *)notification {
    _panel = [[NSPanel alloc] initWithContentRect:CGRectMake(0, 0, 540, 725)
                                       styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable
                                         backing:NSBackingStoreBuffered defer:NO];
    _panel.title = @"Trackpad Gesture Diagnostic";
    _panel.delegate = self;
    _panel.floatingPanel = YES;
    _panel.hidesOnDeactivate = NO;
    _panel.collectionBehavior = NSWindowCollectionBehaviorCanJoinAllSpaces |
                                NSWindowCollectionBehaviorFullScreenAuxiliary;

    NSView *content = _panel.contentView;
    NSButton *stop = [NSButton buttonWithTitle:@"Stop" target:self action:@selector(stop:)];
    stop.keyEquivalent = @"\033";
    [stop setAccessibilityHelp:@"Cancel the countdown or end the active gesture."];
    stop.frame = CGRectMake(18, 597, 504, 32);
    [content addSubview:stop];
    for (Test test = Scroll; test < TestCount; test++) {
        NSString *title = IsDockSwipe(test) ? [Title(test) stringByAppendingString:@" (experimental)"] : Title(test);
        NSButton *button = [NSButton buttonWithTitle:title target:self action:@selector(start:)];
        button.tag = test;
        button.frame = CGRectMake(18, 560 - 33 * test, 504, 30);
        [content addSubview:button];
    }
    _localTarget = [NSButton checkboxWithTitle:@"Use canvas event location (place pointer over canvas)"
                                      target:nil action:NULL];
    _localTarget.state = NSControlStateValueOn;
    _localTarget.frame = NSMakeRect(22, 233, 496, 26);
    [_localTarget setAccessibilityHelp:@"Keep this window active and move the pointer over the canvas during the countdown. Event location alone may not route input there. When unchecked, tests use the pointer position. Dock swipes are always global."];
    [content addSubview:_localTarget];

    NSBox *box = [[NSBox alloc] initWithFrame:NSMakeRect(18, 73, 504, 156)];
    box.title = @"Local gesture test canvas";
    NSTextField *legend = [NSTextField labelWithString:@"Counts since reset. Values show last nonzero samples. Phases and states are bitmasks."];
    legend.font = [NSFont systemFontOfSize:11];
    NSView *boxContent = box.contentView;
    legend.frame = NSMakeRect(6, NSHeight(boxContent.bounds) - 17, NSWidth(boxContent.bounds) - 12, 17);
    _canvas = [[GestureCanvas alloc] initWithFrame:NSMakeRect(0, 0, NSWidth(boxContent.bounds),
                                                              NSMinY(legend.frame))];
    [boxContent addSubview:_canvas];
    [boxContent addSubview:legend];
    [content addSubview:box];

    _status = [NSTextField wrappingLabelWithString:@""];
    _status.frame = CGRectMake(22, 10, 496, 60);
    [content addSubview:_status];
    for (NSView *view in content.subviews) {
        NSRect frame = view.frame;
        frame.origin.y += 80;
        view.frame = frame;
    }
    _routing = [NSTextField wrappingLabelWithString:@"Scroll routing: no test sampled. No AppKit scroll observed."];
    _routing.frame = NSMakeRect(22, 10, 496, 76);
    _routing.font = [NSFont systemFontOfSize:11];
    [content addSubview:_routing];
    // Observe normal application dispatch without changing or delivering events.
    _scrollMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskScrollWheel handler:^NSEvent *(NSEvent *event) {
        NSView *hit = [event.window.contentView hitTest:event.locationInWindow];
        self->_appScrollCount++;
        self->_routing.stringValue = [NSString stringWithFormat:
            @"%@\nAppKit scrolls %lu: window %ld, point (%.0f, %.0f), hit %@.",
            self->_routingStart ?: @"No test sampled.", (unsigned long)self->_appScrollCount,
            (long)event.windowNumber, event.locationInWindow.x, event.locationInWindow.y,
            hit ? NSStringFromClass(hit.class) : @"none"];
        return event;
    }];
    [self status:AXIsProcessTrusted() ? @"Ready. Select a test." : [self permissionMessage]];
    [_panel center];
    [_panel makeKeyAndOrderFront:nil];
    [NSApp activateIgnoringOtherApps:YES];
}

- (NSString *)permissionMessage {
    return @"Enable Trackpad Gesture Diagnostic in System Settings › Privacy & Security › Accessibility, then retry.";
}

- (void)status:(NSString *)message {
    _status.stringValue = message;
    NSAccessibilityPostNotification(_status, NSAccessibilityValueChangedNotification);
}

- (BOOL)post:(Frame)frame {
    CGEventRef event = CreateEvent(_source, _location, frame);
    if (!event) return NO;
    CGEventPost(kCGSessionEventTap, event);
    CFRelease(event);
    return YES;
}

- (BOOL)clearRun {
    [_timer invalidate];
    _timer = nil;
    _frames = nil;
    Frame terminal = StopFrame(_active);
    BOOL closed = terminal.phase == None || [self post:terminal];
    if (closed) _active = (Frame){0};
    return closed;
}

- (void)stop:(id)sender {
    [self status:[self clearRun] ? @"Stopped." : @"Could not create the closing event. Press Stop to retry."];
}

- (void)start:(NSButton *)sender {
    if (![self clearRun]) {
        [self status:@"Could not create the closing event. Press Stop to retry."];
        return;
    }
    if (!AXIsProcessTrusted()) {
        [self status:[self permissionMessage]];
        return;
    }

    _test = sender.tag;
    if (IsDockSwipe(_test) && NSProcessInfo.processInfo.operatingSystemVersion.majorVersion >= 27) {
        [self status:@"This Dock swipe format is unsupported on macOS 27 and later."];
        return;
    }
    if (!_source) _source = CGEventSourceCreate(kCGEventSourceStateCombinedSessionState);
    if (!_source) {
        [self status:@"Could not create a CoreGraphics event source."];
        return;
    }

    _targetsCanvas = TargetsCanvas(_test, _localTarget.state == NSControlStateValueOn);
    _frames = Sequence(_test);
    _next = 0;
    _countdown = 3;
    [self showCountdown];
    [self schedule:1 action:@selector(countDown:)];
}

- (void)schedule:(NSTimeInterval)interval action:(SEL)action {
    _timer = [NSTimer timerWithTimeInterval:interval target:self selector:action userInfo:nil repeats:YES];
    [NSRunLoop.mainRunLoop addTimer:_timer forMode:NSRunLoopCommonModes];
}

- (void)showCountdown {
    NSString *target = _targetsCanvas ? @"Keep this window active. Move the pointer over the canvas now." :
        IsDockSwipe(_test) ? @"Experimental global Dock swipe." : @"Position the pointer over the target.";
    [self status:[NSString stringWithFormat:@"%@ in %ld. %@", Title(_test), (long)_countdown, target]];
}

- (void)countDown:(NSTimer *)timer {
    if (--_countdown > 0) {
        [self showCountdown];
        return;
    }
    [timer invalidate];
    if (!AXIsProcessTrusted()) {
        [self clearRun];
        [self status:[self permissionMessage]];
        return;
    }
    if (_targetsCanvas) {
        if (!_panel.isKeyWindow || !NSApp.isActive || ![_panel makeFirstResponder:_canvas]) {
            [self clearRun];
            [self status:@"Keep the diagnostic window active and retry the local test."];
            return;
        }
        NSRect bounds = _canvas.bounds;
        NSPoint windowPoint = [_canvas convertPoint:NSMakePoint(NSMidX(bounds), NSMidY(bounds)) toView:nil];
        _location = QuartzPoint([_panel convertPointToScreen:windowPoint],
                                CGDisplayBounds(CGMainDisplayID()));
        [_canvas reset];
    } else {
        CGEventRef pointer = CGEventCreate(NULL);
        if (!pointer) {
            [self clearRun];
            [self status:@"Could not sample the pointer location."];
            return;
        }
        _location = CGEventGetLocation(pointer);
        CFRelease(pointer);
    }
    NSPoint pointer = NSEvent.mouseLocation;
    CGPoint quartzPointer = QuartzPoint(pointer, CGDisplayBounds(CGMainDisplayID()));
    NSInteger pointerWindow = [NSWindow windowNumberAtPoint:pointer belowWindowWithWindowNumber:0];
    NSView *pointerHit = pointerWindow == _panel.windowNumber ?
        [_panel.contentView hitTest:[_panel convertPointFromScreen:pointer]] : nil;
    _routingStart = [NSString stringWithFormat:
        @"At zero (Quartz): event (%.0f, %.0f), pointer (%.0f, %.0f). Pointer window %ld; canvas window %ld; pointer hit %@.",
        _location.x, _location.y, quartzPointer.x, quartzPointer.y,
        (long)pointerWindow, (long)_panel.windowNumber,
        pointerHit ? NSStringFromClass(pointerHit.class) : @"outside panel"];
    _appScrollCount = 0;
    _routing.stringValue = [_routingStart stringByAppendingString:
        _scrollMonitor ? @"\nNo AppKit scroll observed since zero." : @"\nScroll monitor unavailable."];
    [self status:[NSString stringWithFormat:@"Running %@.", Title(_test)]];
    [self schedule:1.0 / 30 action:@selector(step:)];
}

- (void)step:(NSTimer *)timer {
    Frame frame = ReadFrame(_frames[_next]);
    if (![self post:frame]) {
        [self status:[self clearRun] ? @"Could not create a test event." :
                     @"Could not create the closing event. Press Stop to retry."];
        return;
    }
    _active = ActiveAfter(frame);
    if (++_next == _frames.count) {
        [self clearRun];
        [self status:_targetsCanvas ? @"Sequence posted. Read the canvas reception counts." :
                     @"Sequence posted. Record what the target did."];
    }
}

- (BOOL)applicationShouldTerminateAfterLastWindowClosed:(NSApplication *)application { return YES; }
- (void)windowWillClose:(NSNotification *)notification { [self clearRun]; }

- (void)applicationWillTerminate:(NSNotification *)notification {
    [self clearRun];
    if (_scrollMonitor) [NSEvent removeMonitor:_scrollMonitor];
    if (_source) CFRelease(_source);
}
@end

#define CHECK(condition) do { \
    if (!(condition)) { fprintf(stderr, "Self-check failed at line %d: %s\n", __LINE__, #condition); return 1; } \
} while (0)

static int SelfCheck(void) {
    CGRect primary = CGRectMake(0, 0, 1440, 900);
    CGPoint location = QuartzPoint(NSMakePoint(200, 600), primary);
    CHECK(CGPointEqualToPoint(location, CGPointMake(200, 300)));
    CHECK(CGPointEqualToPoint(QuartzPoint(NSMakePoint(-240, 1050), primary), CGPointMake(-240, -150)));
    CHECK(CGPointEqualToPoint(QuartzPoint(NSMakePoint(1500, -100), primary), CGPointMake(1500, 1000)));
    CGEventSourceRef source = CGEventSourceCreate(kCGEventSourceStateCombinedSessionState);
    CHECK(source);
    NSUInteger total = 0, stops = 0;
    CHECK(StopFrame((Frame){0}).phase == None);

    for (Test test = Scroll; test < TestCount; test++) {
        CHECK(TargetsCanvas(test, YES) == (test <= SmartZoom));
        CHECK(!TargetsCanvas(test, NO));
        Frame active = {0};
        BOOL sawMomentum = NO, sawTwoAxes = NO, reversed = NO;
        double previousProgress = 0;
        Phase lastPhase = None;

        for (NSValue *value in Sequence(test)) {
            Frame frame = ReadFrame(value);
            CHECK(frame.test == test);
            if (frame.phase == Begin || frame.phase == None) {
                CHECK(active.phase == None);
            } else {
                CHECK(active.phase != None && active.momentum == frame.momentum);
            }

            CGEventRef event = CreateEvent(source, location, frame);
            CHECK(event && CGPointEqualToPoint(CGEventGetLocation(event), location));
            if (test == Scroll) {
                CHECK(CGEventGetType(event) == kCGEventScrollWheel);
                CHECK(CGEventGetIntegerValueField(event, kCGScrollWheelEventIsContinuous) == 1);
                CHECK(CGEventGetIntegerValueField(event, kCGScrollWheelEventScrollPhase) ==
                      (frame.momentum ? 0 : frame.phase));
                CHECK(CGEventGetIntegerValueField(event, kCGScrollWheelEventMomentumPhase) ==
                      (frame.momentum ? (frame.phase == End ? 3 : frame.phase) : 0));
                CHECK(CGEventGetIntegerValueField(event, kCGScrollWheelEventPointDeltaAxis1) == frame.value);
                CHECK(CGEventGetIntegerValueField(event, kCGScrollWheelEventPointDeltaAxis2) == frame.horizontalPixels);
                if (frame.momentum && frame.phase == Begin) CHECK(lastPhase == End);
                sawMomentum |= frame.momentum;
                sawTwoAxes |= frame.value != 0 && frame.horizontalPixels != 0;
            } else {
                BOOL dock = IsDockSwipe(test);
                CHECK(CGEventGetType(event) == (dock ? DockControlType : GestureType));
                CHECK(CGEventGetIntegerValueField(event, GesturePhase) == frame.phase);
                int subtype = dock ? 23 : test == SmartZoom ? 22 : test == Pinch ? 8 : 5;
                CHECK(CGEventGetIntegerValueField(event, GestureSubtype) == subtype);
                if (dock) {
                    CHECK(CGEventGetIntegerValueField(event, SwipeAxis) == (test <= DesktopRight ? 1 : 2));
                    int32_t bits = (int32_t)CGEventGetIntegerValueField(event, SwipeProgressBits);
                    float progress;
                    memcpy(&progress, &bits, sizeof(progress));
                    CHECK(progress == (float)frame.value);
                    CHECK(fabs(CGEventGetDoubleValueField(event, SwipeProgress) - frame.value) < 0.000001);
                    if (frame.phase == Change) {
                        reversed |= fabs(frame.value) < fabs(previousProgress);
                        previousProgress = frame.value;
                    }
                } else if (test != SmartZoom) {
                    CHECK(fabs(CGEventGetDoubleValueField(event, test == Pinch ? Magnification : Rotation)
                               - frame.value) < 0.000001);
                }
            }
            CFRelease(event);
            total++;
            lastPhase = frame.phase;
            active = ActiveAfter(frame);
            Frame terminal = StopFrame(active);
            if (frame.phase == Begin || frame.phase == Change) {
                CHECK(terminal.test == test && terminal.momentum == frame.momentum);
                CHECK(terminal.phase == (frame.momentum ? End : Cancel));
                CHECK(terminal.value == (IsDockSwipe(test) ? frame.value : 0) && terminal.horizontalPixels == 0);
                CGEventRef closing = CreateEvent(source, location, terminal);
                CHECK(closing);
                if (test == Scroll) {
                    CGEventField field = terminal.momentum ? kCGScrollWheelEventMomentumPhase : kCGScrollWheelEventScrollPhase;
                    CHECK(CGEventGetIntegerValueField(closing, field) == (terminal.momentum ? 3 : 8));
                } else {
                    CHECK(CGEventGetIntegerValueField(closing, GesturePhase) == 8);
                }
                CFRelease(closing);
                stops++;
            } else {
                CHECK(terminal.phase == None);
            }
        }

        CHECK(active.phase == None);
        if (test == Scroll) CHECK(sawTwoAxes && sawMomentum);
        if (test == DesktopCancel || test == MissionCancel) {
            CHECK(reversed && lastPhase == Cancel);
        } else if (IsDockSwipe(test)) {
            CHECK(!reversed && lastPhase == End);
            CHECK(previousProgress == ((test == DesktopRight || test == AppExpose) ? -1 : 1));
        } else {
            CHECK(lastPhase == (test == SmartZoom ? None : End));
        }
    }
    CFRelease(source);
    printf("PASS: %ld sequences, %lu event frames, %lu Stop closures, canvas targeting and coordinate checks. No events posted.\n",
           (long)TestCount, (unsigned long)total, (unsigned long)stops);
    return 0;
}

#include "PhoneBridge.h"
#include "ReceiverWindow.h"

int main(int argc, const char *argv[]) {
    @autoreleasepool {
        BOOL diagnostic = argc == 2 && strcmp(argv[1], "--diagnostic") == 0;
        if (argc > 1 && !diagnostic) {
            if (argc == 2 && strcmp(argv[1], "--self-check") == 0) return SelfCheck();
            if (argc == 2 && strcmp(argv[1], "--bridge") == 0) return PhoneBridge(NO);
            if (argc == 2 && strcmp(argv[1], "--bridge-check") == 0) return PhoneBridge(YES);
            fputs("Usage: GestureDiagnostic [--self-check | --diagnostic]\n", stderr);
            return 2;
        }
        NSApplication *app = NSApplication.sharedApplication;
        [app setActivationPolicy:NSApplicationActivationPolicyRegular];
        __attribute__((objc_precise_lifetime)) id<NSApplicationDelegate> delegate = diagnostic ? (id)[Diagnostic new] : [ReceiverWindow new];
        app.delegate = delegate;
        [app run];
        return 0;
    }
}
