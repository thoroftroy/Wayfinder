# Wayfinder changelog

## v0.5.2

- Opening a folder slides in from the right and going back slides from the left; the loading spinner only appears if a folder is slow to read
- Viewer: double-tap zoom animates, panning stops at the photo's edges, and one-finger swipes go to the next photo
- Smoother everywhere: new screens, tabs and Settings ease in instead of snapping, and buttons, tabs, chips and switches give a light tap you can feel (following your phone's touch-feedback setting)
- Android's predictive back: the back gesture shows a live preview of where you're going
- New built-in viewer: photos (swipe between them, pinch and double-tap to zoom), video and music, text and code, and PDFs
- Photos, videos and documents in the Safe folder now open right inside Wayfinder, so they never leave it
- Files open in the built-in viewer when the matching Wonderdesk app isn't installed
- Fixed a crash in the Safe folder and network shares when the list changed while it was on screen
- Storage widget shrinks cleanly to small sizes
- Super look: tap sparkles now match Bliss Player: soft glowing sparks with trails, drifting rainbow colours and a faint aurora, instead of big solid circles

## v0.5.1

- Much faster and smoother: the app is now built optimised (the way Play Store apps are), so screens open quicker, scrolling is fluid and the APK is about a quarter of the size
- Screens and lists no longer decode pictures on the main thread, and skip redrawing parts that didn't change

## v0.5.0

- Fixed a crash when Wi-Fi sharing switched itself off
- Storage widget settings: pick any four shortcuts (Downloads, Camera, Pictures, Documents, Music, Movies, Safe folder, Network, Recycle bin, Internal storage) and the background
- Widget shortcuts now work when Wayfinder is already open
- Works properly in landscape: tabs move to a rail at the side so the content keeps its height, and screens that ran out of room now scroll
- If the app ever crashes it says so next time it opens and offers to send a report (only the error, app version and phone model, with file names and links removed). Turn on automatic sending in Control Room › Wonderdesk
- Lists can no longer crash when two items share a name

## v0.4.0

- The Safe folder has its own password, separate from your screen lock and fingerprint, with a hint, a way to change the password, and a way to erase everything
- Safe folder files use a new chunked format: huge files move in and out with real progress and can be cancelled straight away. Older Safe folders are upgraded automatically
- Copy, move, delete, compress, extract and Safe folder jobs run in the background with a progress notification, time left and a working Cancel button; they keep going when you leave the app
- Search can be stopped at any time
- New Storage widget with shortcuts to Downloads, Camera, Documents and the Safe folder
- Files changed a moment ago say “Just now” instead of “0 minutes ago”
- If the app ever crashes or freezes, a report goes to Control Room so it can be shared and fixed
- App icon art is larger and fills its tile without being cut off

## v0.3.1

- Network: browse Windows, Mac, Linux and NAS shares (SMB 2/3), with passwords stored encrypted
- Share this phone over Wi-Fi with a web page anyone can open in a browser (with uploads) and an FTP server for PCs and file managers. Optional password, read-only mode, and it stops by itself when idle
- Safe folder: move files into an encrypted folder that only opens with your fingerprint, face or screen lock
- USB drives and SD cards appear and disappear live, with an Eject shortcut
- Much faster: folders load in the background so scrolling never waits on the disk, categories come straight from Android's media index, search shows results as it finds them, and thumbnails load in parallel
- App icon redrawn so nothing is cut off: round icons fill the space, the rest sit on an Aqua tile
- Smoother and lighter on the battery: the window background no longer animates, tap effects sleep until you tap, and nothing ticks while the app is in the background
- Opening the app no longer waits on Control Room to read the suite look

## v0.3.0

- Full file manager with all files access
- Copy, move, recycle bin, search and ZIP tools
- Opens files in the matching Wonderdesk app
