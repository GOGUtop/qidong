// Checks that the app uses a session-status media card without audio/video playback
// and never changes its foreground-service type to mediaPlayback.
const fs = require('node:fs');
const assert = require('node:assert/strict');
const java = fs.readFileSync('app/src/main/java/io/github/stlauncher/MediaCardController.java', 'utf8');
const service = fs.readFileSync('app/src/main/java/io/github/stlauncher/ForegroundSessionService.java','utf8');
const manifest = fs.readFileSync('app/src/main/AndroidManifest.xml','utf8');
const main = fs.readFileSync('app/src/main/java/io/github/stlauncher/MainActivity.java','utf8');
assert.match(java,/new MediaSession\(/);
assert.match(java,/new Notification\.MediaStyle\(/);
assert.match(java,/METADATA_KEY_DURATION/);
assert.match(java,/setProgress\(/);
assert.doesNotMatch(java,/AudioTrack|MediaPlayer|requestAudioFocus/);
assert.match(service,/ACTION_DISABLE_MEDIA/);
assert.match(main,/LauncherOptions\.MEDIA_CARD/);
assert.doesNotMatch(manifest,/foregroundServiceType="mediaPlayback"/);
console.log('PASS: media card session-status configuration');
