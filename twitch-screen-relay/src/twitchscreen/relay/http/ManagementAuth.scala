package twitchscreen.relay.http

private[relay] enum HttpAccess:
  case Management, Public, Callback

// Access labels describe endpoint purpose, not authentication. BrowserBoundary
// protects every route before body decoding. Provider callback checks stay local.
