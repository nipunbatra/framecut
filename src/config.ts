// Google OAuth client. The client ID is public by design: tokens are only
// issued to the JavaScript origins registered for this client. No API key is
// needed — the app browses Drive with the user's bearer token via the Drive
// REST API (the Google Picker, which required a developer key, was removed).
export const CONFIG = {
  CLIENT_ID: '754571415429-dve19qtjfntr104sk8a70tb4rt79mgsc.apps.googleusercontent.com',
  // Full Drive access so the app can browse the whole folder tree, open any
  // video, and save trimmed files into any folder.
  SCOPES: 'https://www.googleapis.com/auth/drive',
};
