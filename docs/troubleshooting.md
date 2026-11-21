# Troubleshooting

Step numbers refer to the [setup guide](setup.md).

#### pmca-gui says "No devices found"
- Check **MENU → Setup → USB Connection**: it must be **Mass Storage** or **MTP**, not *PC Remote*.
- Try another USB cable (some cables only charge) or another USB port.
- Close any other software that talks to cameras (Imaging Edge, PlayMemories Home, Photos, Image Capture, Dropbox, Google Drive).
- macOS: install Sony's [Camera Driver for Mac](https://support.d-imaging.sony.co.jp/mac/driver/11/ja/).

#### The camera says "No known Wi-Fi network found"
Make sure the network is saved in the camera ([step 3](setup.md#step-3-connect-the-camera-to-your-wi-fi)), is 2.4 GHz and is in range. The app keeps searching; press **OK** → *Camera Wi-Fi settings* to add one.

#### The camera says "Can't reach Google"
The Wi-Fi works but has no Internet access (or a captive portal, as in hotels). Try another network.

#### The setup page doesn't open
- Your computer or phone must be on the **same** Wi-Fi as the camera, and the app must be open on the camera.
- Type `http://` (not `https://`) and the `:8080` at the end.
- Guest networks often isolate devices from each other; use the main network.

#### Google shows "Error 403: access_denied"
The Cloud app is still in *Testing* mode and your account isn't a test user. Publish it ([step 4.5](setup.md#45-publish-the-app)).

#### Google shows "Error 400: redirect_uri_mismatch"
The client was not created as a **Desktop app**. Create a new one ([step 4.6](setup.md#46-create-the-credentials-file)) and paste its JSON again (section 1 → *Replace the client*).

#### The camera says "Please sign in to Google again"
Google no longer accepts the sign-in: you revoked access in your Google account, changed your password, or the Cloud app was in *Testing* mode for more than 7 days. Open the address shown on the camera and repeat [step 5.4](setup.md#54-sign-in-to-google).

#### The album list is empty or disabled
Albums need the two permissions from [step 4.4](setup.md#44-add-the-permissions-the-app-needs). If you added the second one later, sign in again from the web page. Remember that only albums created with this app are listed.

#### Anything else
The app writes a detailed log to `UPLOADER/LOG.TXT` on the memory card. It never contains passwords or tokens. Include it when [reporting a problem](https://github.com/victor141516/sonia/issues).
