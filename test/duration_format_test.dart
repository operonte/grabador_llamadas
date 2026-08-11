import 'package:flutter_test/flutter_test.dart';
import 'package:grabador_llamadas/duration_format.dart';

void main() {
  group('formatDuration', () {
    test('muestra solo minutos:segundos por debajo de una hora', () {
      expect(formatDuration(Duration.zero), '00:00');
      expect(formatDuration(const Duration(seconds: 5)), '00:05');
      expect(formatDuration(const Duration(minutes: 5, seconds: 23)), '05:23');
      expect(formatDuration(const Duration(minutes: 59, seconds: 59)), '59:59');
    });

    test('agrega las horas al llegar a los 60 minutos', () {
      // Antes de corregir este bug, una llamada de 1h05m23s se mostraba
      // como "05:23" (perdiendo la hora), dando información falsa.
      expect(formatDuration(const Duration(hours: 1)), '1:00:00');
      expect(
        formatDuration(const Duration(hours: 1, minutes: 5, seconds: 23)),
        '1:05:23',
      );
      expect(
        formatDuration(const Duration(hours: 2, minutes: 30)),
        '2:30:00',
      );
    });
  });
}
