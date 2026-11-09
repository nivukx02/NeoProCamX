# CamX product site

Editorial product website positioning CamX as a cinema camera for mobile.

## Preview

Serve the repository root:

```powershell
python -m http.server 4173
```

Open `http://localhost:4173/website/`.

The site uses no app screenshots, generated photographs, or flat scene illustrations. Its main visual system is a coordinated set of real-time WebGL reconstructions. The 3D phone screen is drawn at runtime from the real CamX UI hierarchy: device-truth status, PROV/P010/LAB/NATIVE chips, manual EV/ISO/shutter/WB/focus/meter controls, XDR rail, and the three-button capture dock.

The checked-in `app.bundle.js` contains the core Three.js, React Three Fiber, Drei, Lenis, and Motion runtime. `boot.js` defers that 3D experience until browser idle time or the first user interaction, allowing the product copy and controls to render first. Below-the-fold WebGL stages mount only when they approach the viewport. GSAP progressively enhances scroll motion when its CDN scripts are available.

Four coordinated WebGL surfaces make up the experience: a caged CamX cinema rig, a responsive operator volume, the four-scene Format Lab, and a dedicated XDR latitude volume. The Format Lab reconstructs a digital 25 fps path from sensor scan through processor, frame buffer, and encoder; a 13-layer XDR exposure stack; a five-group glass assembly with a nine-blade iris; and a 1,024-cell 10-bit silicon die. Every tab replaces the full three-dimensional mechanism rather than recoloring one generic scene. The interactive stages support pointer orbit and keyboard tab navigation.

The dynamic-range comparison uses one locally stored 1920 × 1080 CC0 SDR photograph for all four treatments, so the source pixels, crop, lens, and light remain identical. Its GPU renderer ports the project-calibrated XDR10+ and NIVUKX VISION parameters through a display-referred SDR compatibility layer that exposure-normalizes the coded tone response around the JPEG diffuse proxy. It also applies edge-aware noise reduction, guarded optical-detail recovery, measured colour corrections, hue-preserving colour volume, and highlight protection. XDR10+ retains its 5,000-nit mastering target and NIVUKX VISION its 10,000-nit target; these describe the HDR master rather than literal browser luminance. HDR10+ and Dolby Vision are illustrative reference grades, not certified emulations or Dolby’s licensed playback engine.

The hero phone uses a real-time render texture beneath the CamX control overlay, so its viewfinder is a live three-dimensional set rather than a painted preview. The product model includes a machined cage, top handle, side grip, accessory module, base plate, and support rods. The operator stage uses a procedural cine-lens still life with geared focus rings, optical glass, a practical lamp, prism, sensor gate, exposure planes, and a digital signal bus. Mobile canvases receive dedicated camera distance and field-of-view values instead of simply shrinking the desktop composition.

The product direction was benchmarked against the official flagship camera pages from Apple, Samsung, Google Pixel, OPPO, vivo, and Xiaomi. CamX uses the strongest shared principles—product scale, disciplined typography, evidence-led chapters, restrained interaction, and explicit technical proof—without copying their layouts, assets, or claims.

The Gallery contains no embedded photo or video preview. It is a single external full-resolution media portal for CamX photographs, motion studies, RAW material, LOG masters, and finished exports.

The Google Play buttons currently use the working CamX Play Store search URL. When the final app listing is available, replace all three `https://play.google.com/store/search?q=CamX&c=apps` links in `index.html` with the listing URL.

Contact enquiries and field-programme messages use `nivukx01@gmail.com`. The creator contact section also presents [nivukx.dev](https://nivukx.dev/) as a future project.

## Production notes

- `production.js` supplies menu focus management, Escape-to-close behavior, active-section navigation, recording-state labels, external-link safety, and a native scroll-progress fallback when GSAP is unavailable.
- Search and social metadata, a data-URI favicon, strict referrer handling, visible keyboard focus, reduced-motion support, high-contrast support, and 280 px through ultrawide layouts are included.
- The CDN motion layer is progressive enhancement. Core content, navigation, the media portal, and the local 3D runtime remain available without it.
- Before public release, replace the Google Play search links with the final package listing URL and add deployment-specific canonical, Open Graph URL, and social-preview image values.
- `assets/camx-dynamic-range-master.jpg` is “Clouds over a meandering river” by Redd Angelo, distributed as CC0 through Wikimedia Commons. The live page retains a source link even though attribution is not required.

The Android application is not built or modified by the website.
