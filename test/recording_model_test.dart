import 'package:flutter_test/flutter_test.dart';
import 'package:grabador_llamadas/recordings_page.dart';

void main() {
  group('Recording.fromMap', () {
    test('parsea los campos que envía el lado nativo', () {
      final recording = Recording.fromMap({
        'uri': 'content://media/external/video/media/42',
        'name': 'grabacion_20260810_120000.mp4',
        'dateAddedSeconds': 1754841600,
        'durationMs': 65000,
        'sizeBytes': 12 * 1024 * 1024,
      });

      expect(recording.uri, 'content://media/external/video/media/42');
      expect(recording.name, 'grabacion_20260810_120000.mp4');
      expect(recording.duration, const Duration(seconds: 65));
      expect(recording.formattedDuration, '01:05');
      expect(recording.formattedSize, '12.0 MB');
    });

    test('usa valores por defecto si duration/size faltan (respaldo nativo aún no calculó)', () {
      final recording = Recording.fromMap({
        'uri': 'content://media/external/video/media/1',
        'name': null,
        'dateAddedSeconds': 1754841600,
        'durationMs': null,
        'sizeBytes': null,
      });

      expect(recording.name, 'grabacion.mp4');
      expect(recording.duration, Duration.zero);
      expect(recording.formattedDuration, '00:00');
      expect(recording.sizeBytes, 0);
    });

    test('no truena si faltan uri o dateAddedSeconds (a diferencia de los demás campos, '
        'estos no tenían fallback y rompían toda la lista)', () {
      final recording = Recording.fromMap({
        'name': 'grabacion_20260810_120000.mp4',
        'durationMs': 65000,
        'sizeBytes': 12 * 1024 * 1024,
      });

      expect(recording.uri, '');
      expect(recording.dateAdded, DateTime.fromMillisecondsSinceEpoch(0));
    });
  });
}
