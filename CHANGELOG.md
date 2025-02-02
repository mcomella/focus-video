# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](http://keepachangelog.com/en/1.0.0/)
and this project adheres to [Semantic Versioning](http://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [2.2] - 2018-08-21
*Released to Fire TV Cube and Fire TV 4K.*

### Added
- Voice commands to control video playback state: "Alexa
play/pause/rewind/fast-forward/restart/next/previous"

### Changed
- Require Amazon voice control permission: `com.amazon.permission.media.session.voicecommandcontrol`

### Fixed
- Media will autoplay, preventing black screens between videos on YouTube (#586)

## Note on releases below
The CHANGELOG entries for the releases listed below this were added retroactively and may be incomplete.

## [2.1] - 2018-?-?
### Added
- Crash reporting with Sentry (see project docs for more information; #429)

### Changed
- Require `android.permission.ACCESS_NETWORK_STATE` for Sentry crash reporting
- Improve Your Rights page

### Fixed
- Made cursor behavior smoother (#472)
- Various performance improvements

## [2.0.1] - 2018-?-?
### Fixed
- Top crasher that doesn't seem to require any specific user interaction (#694)

## [2.0] - 2018-?-?
### Added
- Ability to pin sites to the Firefox home screen
- Ability to remove sites from the Firefox home screen

### Changed
- Improve navigation controls for web browsing

## [1.1.2] - 2018-02-?
### Fixed
- Crash when leaving the app when video is fullscreened
- Various German translations

## [1.1.1] - 2018-02-?
### Changed
- A URL to ensure users see the best formatted website

## [1.1] - 2018-01-?
### Added
- Turbo mode
- Better support for VoiceView screen reader features

## [1.0.1] - 2017-12-?
### Fixed
- Icon artifacts on older versions of Android
- Various stability issues

## [1.0] - 2017-12-20
*Initial release! A browser including home tile shortcuts.*

[Unreleased]: https://github.com/mozilla-mobile/firefox-tv/compare/v2.2...HEAD
[2.2]: https://github.com/mozilla-mobile/firefox-tv/compare/v2.1...v2.2
[2.1]: https://github.com/mozilla-mobile/firefox-tv/compare/v2.0.1...v2.1
[2.0.1]: https://github.com/mozilla-mobile/firefox-tv/compare/v2.0...v2.0.1
[2.0]: https://github.com/mozilla-mobile/firefox-tv/compare/v1.1.2...v2.0
[1.1.2]: https://github.com/mozilla-mobile/firefox-tv/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/mozilla-mobile/firefox-tv/compare/v1.1...v1.1.1
[1.1]: https://github.com/mozilla-mobile/firefox-tv/compare/v1.0.1...v1.1
[1.0.1]: https://github.com/mozilla-mobile/firefox-tv/compare/v1.0-RC1...v1.0.1
[1.0]: https://github.com/mozilla-mobile/firefox-tv/compare/a220db99ea9bd3c05d3750d9c52c3a2d7356698d...v1.0-RC1
