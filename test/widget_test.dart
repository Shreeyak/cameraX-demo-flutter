import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:camerax_demo_flutter/main.dart';

void main() {
  testWidgets('CameraXDemoApp smoke test — renders without crashing', (
    WidgetTester tester,
  ) async {
    // Stub the MethodChannel so initCamera() doesn't throw
    // MissingPluginException when requestPermission is called.
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      const MethodChannel('com.example.camerax/control'),
      (call) async {
        if (call.method == 'requestPermission') return false;
        return null;
      },
    );

    await tester.pumpWidget(const CameraXDemoApp());
    // Give a single frame for the async initCamera() to begin.
    await tester.pump();

    // The app should render the permission-denied message when the stub
    // returns false.
    expect(find.text('Camera permission required'), findsOneWidget);
  });
}
