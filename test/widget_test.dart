import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:grabador_llamadas/main.dart';

void main() {
  testWidgets('Muestra el botón de grabar en estado inicial', (WidgetTester tester) async {
    await tester.pumpWidget(const MyApp());

    expect(find.text('Detenido'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Grabar'), findsOneWidget);
  });
}
