import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class Recording {
  final String uri;
  final String name;
  final DateTime dateAdded;
  final Duration duration;
  final int sizeBytes;

  Recording({
    required this.uri,
    required this.name,
    required this.dateAdded,
    required this.duration,
    required this.sizeBytes,
  });

  factory Recording.fromMap(Map<dynamic, dynamic> map) {
    return Recording(
      uri: map['uri'] as String,
      name: map['name'] as String? ?? 'grabacion.mp4',
      dateAdded: DateTime.fromMillisecondsSinceEpoch(
        (map['dateAddedSeconds'] as int) * 1000,
      ),
      duration: Duration(milliseconds: map['durationMs'] as int? ?? 0),
      sizeBytes: map['sizeBytes'] as int? ?? 0,
    );
  }

  String get formattedDuration {
    final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
    final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
    return '$minutes:$seconds';
  }

  String get formattedSize {
    final mb = sizeBytes / (1024 * 1024);
    return '${mb.toStringAsFixed(1)} MB';
  }

  String get formattedDate {
    final d = dateAdded;
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(d.day)}/${two(d.month)}/${d.year} ${two(d.hour)}:${two(d.minute)}';
  }
}

class RecordingsPage extends StatefulWidget {
  const RecordingsPage({super.key});

  @override
  State<RecordingsPage> createState() => _RecordingsPageState();
}

class _RecordingsPageState extends State<RecordingsPage> {
  static const _channel = MethodChannel('grabador_llamadas/screen_record');

  List<Recording>? _recordings;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final raw = await _channel.invokeMethod<List<dynamic>>('listRecordings') ?? [];
    if (!mounted) return;
    setState(() {
      _recordings = raw.map((e) => Recording.fromMap(e as Map)).toList();
    });
  }

  Future<void> _delete(Recording recording) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('¿Borrar esta grabación?'),
        content: Text(recording.name),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancelar')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Borrar')),
        ],
      ),
    );
    if (confirmed != true) return;

    final ok = await _channel.invokeMethod<bool>('deleteRecording', {'uri': recording.uri}) ?? false;
    if (ok) _load();
  }

  @override
  Widget build(BuildContext context) {
    final recordings = _recordings;
    return Scaffold(
      appBar: AppBar(title: const Text('Mis grabaciones')),
      body: recordings == null
          ? const Center(child: CircularProgressIndicator())
          : recordings.isEmpty
              ? const Center(child: Text('Todavía no hay grabaciones'))
              : ListView.builder(
                  itemCount: recordings.length,
                  itemBuilder: (context, index) {
                    final recording = recordings[index];
                    return ListTile(
                      leading: const Icon(Icons.videocam),
                      title: Text(recording.formattedDate),
                      subtitle: Text('${recording.formattedDuration} · ${recording.formattedSize}'),
                      onTap: () => _channel.invokeMethod('openRecording', {'uri': recording.uri}),
                      trailing: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          IconButton(
                            icon: const Icon(Icons.share),
                            onPressed: () => _channel.invokeMethod('shareRecording', {'uri': recording.uri}),
                          ),
                          IconButton(
                            icon: const Icon(Icons.delete_outline),
                            onPressed: () => _delete(recording),
                          ),
                        ],
                      ),
                    );
                  },
                ),
    );
  }
}
