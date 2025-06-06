/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.openide.util;

import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ImageObserver;
import java.awt.image.MultiResolutionImage;
import java.awt.image.RGBImageFilter;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.io.Serial;
import java.lang.ref.SoftReference;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.openide.util.spi.SVGLoader;

/** 
 * Useful static methods for manipulation with images/icons, results are cached.
 *
 * <p>Images can be represented as instances of either {@link Image} or {@link Icon}. For best
 * results on HiDPI displays, clients should use the {@link #image2Icon(Image)} method provided by
 * this class when converting an {@code Image} to an {@code Icon}, rather than constructing
 * {@link ImageIcon} instances themselves. When doing manual painting, clients should use
 * {@link Icon#paintIcon(Component, Graphics, int, int)} rather than
 * {@link Graphics#drawImage(Image, int, int, ImageObserver)}.
 * 
 * @author Jaroslav Tulach, Tomas Holy
 * @since 7.15
 */
public final class ImageUtilities {

    /**
     * Property that holds URL of the image bits.
     * @since 9.24
     */
    public static final String PROPERTY_URL = "url"; // NOI18N
    
    private static final Logger LOGGER = Logger.getLogger(ImageUtilities.class.getName());

    /** separator for individual parts of tool tip text */
    static final String TOOLTIP_SEPAR = "<br>"; // NOI18N
    /** a value that indicates that the icon does not exists */
    private static final ActiveRef<String> NO_ICON = new ActiveRef<>(null, null, null);

    private static final Map<String, ActiveRef<String>> cache = new HashMap<>(128);
    private static final Map<String, ActiveRef<String>> localizedCache = new HashMap<>(128);
    private static final Map<CompositeImageKey, ActiveRef<CompositeImageKey>> compositeCache = new HashMap<>(128);
    private static final Map<ToolTipImageKey, ActiveRef<ToolTipImageKey>> imageToolTipCache = new HashMap<>(128);

    private static RGBImageFilter imageIconFilter = null;

    /** Resource paths for which we have had to strip initial slash.
     * @see "#20072"
     */
    private static final Set<String> extraInitialSlashes = new HashSet<>();
    private static final CachedLookupLoader<ClassLoader> classLoaderLoader = new CachedLookupLoader<>(ClassLoader.class);
    private static final CachedLookupLoader<SVGLoader> svgLoaderLoader = new CachedLookupLoader<>(SVGLoader.class);
    private static final Component component = new Component() {
    };

    private static final MediaTracker tracker = new MediaTracker(component);
    private static int mediaTrackerID;
    
    private static ImageReader PNG_READER;
    
    private static final String DARK_LAF_SUFFIX = "_dark"; //NOI18N

    /**
     * Dummy component to be passed to the first parameter  of
     * {@link Icon#paintIcon(Component, Graphics, int, int)} when converting an {@code Icon} to an
     * {@code Image}. See comment in {@link #icon2ToolTipImage(Icon, URL)}.
     */
    private static volatile Component dummyIconComponentLabel;

    /**
     * Second dummy component. Some {@link Icon#paintIcon(java.awt.Component, java.awt.Graphics, int, int)} are very picky and downcast the
     * Component to a specific subclass. JCheckBox will satisfy checkboxes, abstract buttons etc. Will not eliminate all cases, but helps.
     * 
     */
    private static volatile Component dummyIconComponentButton;

    static {
        /* Could have used Mutex.EVENT.writeAccess here, but it doesn't seem to be available during
        testing. */
        if (EventQueue.isDispatchThread()) {
            dummyIconComponentLabel = new JLabel();
            dummyIconComponentButton = new JCheckBox();
        } else {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    dummyIconComponentLabel = new JLabel();
                    dummyIconComponentButton = new JCheckBox();
                }
            });
        }
    }
    
    private ImageUtilities() {
    }

    static {
        ImageIO.setUseCache(false);
        PNG_READER = ImageIO.getImageReadersByMIMEType("image/png").next();
    }

    /**
     * Loads an image from the specified resource path. The image is loaded using the "system"
     * classloader registered in Lookup.
     *
     * <p>If the current look and feel is 'dark' (<code>UIManager.getBoolean("nb.dark.theme")</code>)
     * then the method first attempts to load image
     * <i>&lt;original file name&gt;<b>_dark</b>.&lt;original extension&gt;</i>. If such file
     * doesn't exist the default one is loaded instead.
     *
     * <p>If the default lookup contains a service provider for the {@link SVGLoader} interface, and
     * there exists an SVG version of the requested image (e.g. "icon.svg" exists when "icon.png"
     * was requested), the SVG version will be loaded instead of the originally requested bitmap.
     * SVG images can also be requested directly. The SVG document's root element must contain
     * explicit width/height attributes. An SVG loader implementation can be installed via the
     * optional {@code openide.util.ui.svg} module.
     *
     * <p>To paint SVG images at arbitrary resolutions, convert the returned {@link Image} to an
     * {@link Icon} using {@link #image2Icon(Image)}, and set an appropriate transform on the
     * {@link Graphics2D} instance passed to {@link Icon#paintIcon(Component, Graphics, int, int)}.
     * When painting on HiDPI-capable {@code Graphics2D} instances provided by Swing, the
     * appropriate transform will already be in place.
     *
     * <p>Since version 8.12 the returned image object responds to call
     * <code>image.getProperty({@link #PROPERTY_URL}, null)</code> by returning the internal
     * {@link URL} of the found and loaded <code>resource</code>. The convenience method
     * {@link #findImageBaseURL} should be used in preference to direct property access.
     *
     * <p>Caching of loaded images can be used internally to improve performance.
     *
     * @param resourceID resource path of the icon (no initial slash)
     * @return icon's Image, or null, if the icon cannot be loaded.     
     */
    public static final Image loadImage(String resourceID) {
        return loadImage(resourceID, false);
    }
    
    /**
     * Loads an image based on a resource path.
     * Exactly like {@link #loadImage(String)} but may do a localized search.
     * For example, requesting <code>org/netbeans/modules/foo/resources/foo.gif</code>
     * might actually find <code>org/netbeans/modules/foo/resources/foo_ja.gif</code>
     * or <code>org/netbeans/modules/foo/resources/foo_mybranding.gif</code>.
     *
     * @param resource resource path of the image (no initial slash)
     * @param localized true for localized search
     * @return the icon's Image, or null if the icon cannot be loaded
     */
    public static final Image loadImage(String resource, boolean localized) {
        return loadImageInternal(resource, localized);
    }

    /**
     * Load an image from a URI/URL. If the URI uses the {@code nbresloc} or {@code nbres}
     * protocols, it is loaded using the resource loading mechanism provided by
     * {@link #loadImage(String,boolean)}, with and without localization, respectively. This
     * includes handling of SVG icons and dark mode variations.
     *
     * <p>This method is intended for use only when a URL or URI must be used instead of a resource
     * path, e.g. in the implementation of pre-existing NetBeans APIs. External URLs should be
     * avoided, as they may be disallowed in the future. Do not use this method for new code; prefer
     * image loading by resource paths instead (e.g. {@link #loadImage(String)}).
     *
     * @param uri the URI of the image, possibly with the nbresloc or nbres protocol
     * @return the loaded image, or either null or an uninitialized image if the image was not
     *         available
     * @since 7.36
     */
    public static final Image loadImage(URI uri) {
        Parameters.notNull("icon", uri);
        String scheme = uri.getScheme();
        if (scheme.equals("nbresloc")) { // NOI18N
            // Apply our dedicated handling logic. Omit the initial slash of the path.
            return loadImage(uri.getPath().substring(1), true);
        } else if (scheme.equals("nbres")) { // NOI18N
            // Same except with localized = false.
            return loadImage(uri.getPath().substring(1), false);
        } else {
            if (!(scheme.equals("file") ||
                scheme.equals("jar") && uri.toString().startsWith("jar:file:") ||
                scheme.equals("file")))
            {
                LOGGER.log(Level.WARNING, "loadImage(URI) called with unusual URI: {0}", uri);
            }
            try {
                /* Observed to return an image with size (-1, -1) if URL points to a non-existent
                file (after ensureLoaded(Image) is called). */
                return Toolkit.getDefaultToolkit().createImage(uri.toURL());
            } catch (MalformedURLException e) {
                LOGGER.log(Level.WARNING, "Malformed URL passed to loadImage(URI)", e);
                return null;
            }
        }
    }

    /**
     * Convert an {@link Icon} instance to a delegating {@link ImageIcon} instance. If the supplied
     * Icon instance is an SVG icon or has other HiDPI capabilities provided by the methods in this
     * class, they are preserved by the conversion.
     *
     * <p>This method is intended for use only when existing APIs require the use of ImageIcon. In
     * most other situations it is preferable to use the more general Icon type.
     *
     * @param icon the icon to be converted; may <em>not</em> be null
     * @return the converted instance
     * @since 7.36
     */
    public static final ImageIcon icon2ImageIcon(Icon icon) {
        if (icon == null) {
            /* Log a warning and return null rather than throwing an exception here. That way,
            refactoring of existing code that wraps an existing expression in icon2ImageIcon will
            not break existing functionality even if it accidentally relied on being able to pass
            null in the past. */
            LOGGER.log(Level.WARNING, "Passing null to icon2ImageIcon is not permitted",
                    new NullPointerException());
            return null;
        }
        if (icon instanceof ImageIcon imageIcon) {
            return imageIcon;
        } else if (icon instanceof ToolTipImage tti) {
            return tti.asImageIcon();
        } else {
            Image image = icon2Image(icon);
            return image instanceof ToolTipImage tti
                    ? tti.asImageIcon()
                    : assignToolTipToImageInternal(image, "").asImageIcon();
        }
    }

    /* Private version of the method showing the more specific return type. We always return a
    ToolTipImage, to take advantage of its rendering tweaks for HiDPI screens. */
    private static ToolTipImage loadImageInternal(String resource, boolean localized) {
        // Avoid a NPE that could previously occur in the isDarkLaF case only. See NETBEANS-2401.
        if (resource == null) {
            return null;
        }
        ToolTipImage image = null;
        if( isDarkLaF() ) {
            image = getIcon(addDarkSuffix(resource), localized);
            // found an image with _dark-suffix, so there no need to apply an
            // image filter to make it look nice using dark themes
        }
        if (null == image) {
            image = getIcon(resource, localized);
            // only non _dark images need filtering
            RGBImageFilter imageFilter = getImageIconFilter();
            if (null != image && null != imageFilter) {
                image = icon2ToolTipImage(FilteredIcon.create(imageFilter, image), image.url);
            }
        }
        return image;
    }

    /**
     * Loads an icon based on a resource path. Similar to {@link #loadImage(String, boolean)}, but
     * returns {@link ImageIcon} instead of {@link Image}.
     *
     * <p>When a general Icon instance can be used rather than more specifically an ImageIcon, it is
     * recommended to use {@link #loadIcon(java.lang.String, boolean)} instead. This method remains
     * for compatibility.
     * 
     * @param resource resource path of the icon (no initial slash)
     * @param localized true for localized search
     * @return ImageIcon or null, if the icon cannot be loaded.
     * @since 7.22
     */
    public static final ImageIcon loadImageIcon( String resource, boolean localized ) {
        ToolTipImage image = loadImageInternal(resource, localized);
        if( image == null ) {
            return null;
        }
        return image.asImageIcon();
    }

    /**
     * Loads an icon based on a resource path. Similar to {@link #loadImage(String, boolean)}, but
     * returns {@link Icon} instead of {@link Image}.
     *
     * @param resource resource path of the icon (no initial slash)
     * @param localized true for localized search
     * @return ImageIcon or null, if the icon cannot be loaded.
     * @since 7.36
     */
    public static final Icon loadIcon( String resource, boolean localized ) {
        ToolTipImage image = loadImageInternal(resource, localized);
        if( image == null ) {
            return null;
        }
        return image.asImageIconIfRequiredForRetina();
    }

    /**
     * Loads an icon based on a resource path. Similar to {@link #loadImage(String)}, but returns
     * {@link Icon} instead of {@link Image}.
     *
     * @param resource resource path of the icon (no initial slash)
     * @return ImageIcon or null, if the icon cannot be loaded.
     * @since 7.36
     */
    public static final Icon loadIcon( String resource ) {
        return loadIcon(resource, false);
    }
    
    private static boolean isDarkLaF() {
        return UIManager.getBoolean("nb.dark.theme"); //NOI18N 
    }
    
    /**
     * 
     * @param resourceName
     * @return 
     * @since 8.35
     */
    private static String addDarkSuffix( String resourceName ) {
        int dotIndex = resourceName.lastIndexOf('.');
        if( dotIndex > 0 ) {
            return resourceName.substring(0, dotIndex) + DARK_LAF_SUFFIX + resourceName.substring(dotIndex);
        }
        return resourceName + DARK_LAF_SUFFIX;
    }

    private static RGBImageFilter getImageIconFilter() {
        if( null == imageIconFilter ) {
            Object obj = UIManager.get( "nb.imageicon.filter"); //NOI18N
            if (obj instanceof RGBImageFilter filter) {
                imageIconFilter = filter;
            }
        }
        return imageIconFilter;
    }

    /** This method merges two images into the a one. The second image is drawn
     * over the first one with its top-left corner at x, y. Images need not be of the same size.
     * The new image will have a size of max(second image size + top-left corner, first image size).
     * This method is used mostly when second image contains transparent pixels (e.g. for badging).
     *
     * <p>The implementation attempts to find the merged image in the cache first, then creates the
     * image if it was not found.
     *
     * @param image1 underlying image
     * @param image2 second image
     * @param x x position of top-left corner
     * @param y y position of top-left corner
     * @return new merged image
     */    
    public static final Image mergeImages(Image image1, Image image2, int x, int y) {
        if (image1 == null || image2 == null) {
            throw new NullPointerException();
        }
        
        CompositeImageKey k = new CompositeImageKey(image1, image2, x, y);
        ToolTipImage cached;

        synchronized (compositeCache) {
            ActiveRef<CompositeImageKey> r = compositeCache.get(k);
            if (r != null) {
                cached = r.get();
                if (cached != null) {
                    return cached;
                }
            }
            cached = doMergeImages(image1, image2, x, y);
            compositeCache.put(k, new ActiveRef<>(cached, compositeCache, k));
            return cached;
        }
    }

    /** This method merges two icons into a new one. The second icon is drawn
     * over the first one with its top-left corner at x, y. Icons need not be of the same size.
     * The new icon will have a size of max(second icon size + top-left corner, first icon size).
     * This method used mostly when second icon contains transparent pixels (e.g. for badging).
     *
     * <p>Similar to {@link #mergeImages(Image, Image, int, int)}, but on {@link Icon} instances
     * rather than {@link Image} instances. This method is provided as a shortcut to avoid the need
     * for conversions in client code.
     *
     * @param icon1 underlying icon
     * @param icon2 second icon
     * @param x x position of top-left corner
     * @param y y position of top-left corner
     * @return new merged icon
     * @since 7.36
     */
    public static final Icon mergeIcons(Icon icon1, Icon icon2, int x, int y) {
        if (icon1 == null || icon2 == null) {
            throw new NullPointerException();
        }
        /* Use the Image-based mergeImages implementation rather than just creating a new
        MergedIcon instance, to take advantage of the former's cache. Icon instances tend to get
        converted back and forth between Icon and Image anyway. */
        return image2Icon(mergeImages(icon2Image(icon1), icon2Image(icon2), x, y));
    }

    /**
     * Converts given image to an icon.
     * @param image to be converted
     * @return icon corresponding icon
     */    
    public static final Icon image2Icon(Image image) {
        ToolTipImage ret = image instanceof ToolTipImage tti ? tti : assignToolTipToImageInternal(image, "");
        return ret.asImageIconIfRequiredForRetina();
    }
    
    /**
     * Converts given icon to a {@link java.awt.Image}.
     *
     * <p>A scalable {@link Icon} instance can always be recovered by passing the returned
     * {@code Image} to {@link #image2Icon(Image)} again, i.e. for painting on HiDPI screens.
     *
     * @param icon {@link javax.swing.Icon} to be converted.
     */
    public static final Image icon2Image(Icon icon) {
        if (icon == null) {
            LOGGER.log(Level.WARNING, null, new NullPointerException());
            return loadImage("org/openide/nodes/defaultNode.png", true);
        }
        if (icon instanceof ToolTipImage tti) {
            return tti;
        } else if (icon instanceof IconImageIcon iconImageIcon) {
            return icon2Image(iconImageIcon.getDelegateIcon());
        } else if (icon instanceof ImageIcon imageIcon) {
            Image ret = imageIcon.getImage();
            if (ret != null)
                return ret;
        }
        return icon2ToolTipImage(icon, null);
    }

    /**
     * @param url may be null
     */
    private static ToolTipImage icon2ToolTipImage(Icon icon, URL url) {
        Parameters.notNull("icon", icon);
        if (icon instanceof ToolTipImage tti) {
            return tti;
        }
        ToolTipImage image = new ToolTipImage(icon, "", url, BufferedImage.TYPE_INT_ARGB);
        Graphics g = image.getGraphics();
        /* Previously, we'd create a new JLabel here every time; this once led to a deadlock on
        startup when the nb.imageicon.filter setting was enabled. The underlying problem is that
        methods in this class may be called from any thread, while JLabel's methods and constructors
        should really only be called on the Event Dispatch Thread. Constructing the component once
        on the EDT fixed the problem. Read-only operations from non-EDT threads shouldn't really be
        a problem; most Icon implementations won't ever access the component parameter anyway. */
        try {
            icon.paintIcon(dummyIconComponentLabel, g, 0, 0);
        } catch (ClassCastException ex) {
            // java.desktop/javax.swing.plaf.metal.OceanTheme$IFIcon.paintIcon assumes a different component,
            // so let's try second most used one type, it satisfies AbstractButton, JCheckbox. Not all cases are
            // covered, however.
            icon.paintIcon(dummyIconComponentButton, g, 0, 0);
        } finally {
            g.dispose();
        }
        return image;
    }
    
    /**
     * Assign tool tip text to given image (creates new or returns cached, original remains unmodified).
     * Text can contain HTML tags e.g. "&#60;b&#62;my&#60;/b&#62; text"
     * @param image image to which tool tip should be set
     * @param text tool tip text
     * @return Image with attached tool tip 
     */    
    public static final Image assignToolTipToImage(Image image, String text) {
        return assignToolTipToImageInternal(image, text);
    }

    // Private version with more specific return type.
    private static ToolTipImage assignToolTipToImageInternal(Image image, String text) {
        Parameters.notNull("image", image);
        Parameters.notNull("text", text);
        ToolTipImageKey key = new ToolTipImageKey(image, text);
        ToolTipImage cached;
        synchronized (imageToolTipCache) {
            ActiveRef<ToolTipImageKey> r = imageToolTipCache.get(key);
            if (r != null) {
                cached = r.get();
                if (cached != null) {
                    return cached;
                }
            }
            cached = ToolTipImage.createNew(text, image, null);
            imageToolTipCache.put(key, new ActiveRef<>(cached, imageToolTipCache, key));
            return cached;
        }
    }

    /**
     * Get tool tip text for given image
     * @param image image which is asked for tool tip text
     * @return String containing attached tool tip text
     */
    public static final String getImageToolTip(Image image) {
        return image instanceof ToolTipImage toolTipImage ? toolTipImage.toolTipText : "";
    }

    /**
     * Add text to tool tip for given image (creates new or returns cached, original remains unmodified).
     * Text can contain HTML tags e.g. "&#60;b&#62;my&#60;/b&#62; text"
     * @param text text to add to tool tip
     * @return Image with attached tool tip
     */
    public static final Image addToolTipToImage(Image image, String text) {
        if (image instanceof ToolTipImage tti) {
            StringBuilder str = new StringBuilder(tti.toolTipText);
            if (str.length() > 0 && text.length() > 0) {
                str.append(TOOLTIP_SEPAR);
            }
            str.append(text);
            return assignToolTipToImage(image, str.toString());
        } else {
            return assignToolTipToImage(image, text);
        }
    }

    /**
     * Creates disabled (color saturation lowered) icon.
     *
     * @param icon original icon used for conversion
     * @return less saturated Icon
     * @since 7.28
     */
    public static Icon createDisabledIcon(Icon icon)  {
        Parameters.notNull("icon", icon);
        /* FilteredIcon's Javadoc mentions a caveat about the Component parameter that is passed to
        Icon.paintIcon. It's not really a problem; previous implementations had the same
        behavior. */
        return FilteredIcon.create(isDarkLaF()
                ? DisabledButtonFilter.INSTANCE_DARK : DisabledButtonFilter.INSTANCE_LIGHT, icon);
    }

    /**
     * Creates disabled (color saturation lowered) image.
     *
     * @param image original image used for conversion
     * @return less saturated Image
     * @since 7.28
     */
    public static Image createDisabledImage(Image image)  {
        Parameters.notNull("image", image);
        // Go through FilteredIcon to preserve scalable icons.
        return icon2Image(createDisabledIcon(image2Icon(image)));
    }
    
    /**
     * Attempts to find image's URL, if it is defined. Image Observer features
     * are not used during this call, the property is assumed to be populated. Note that
     * the URL may be specific for a localization or branding, and may be the same for
     * bare and badged icons.
     * 
     * @param image image to inspect
     * @return image's URL or {@code null} if not defined.
     * @since 9.24
     */
    public static URL findImageBaseURL(Image image) {
      return image.getProperty(PROPERTY_URL, null) instanceof URL url ? url : null;
    }
    
    /**
     * Get an SVG icon loader, if the appropriate service provider module is installed. To ensure
     * lazy loading of the SVG loader module, this method should only be called when there actually
     * exists an SVG file to load. The result is cached.
     *
     * @return may be null
     */
    private static SVGLoader getSVGLoader() {
        /* "Objects contained in the default lookup are instantiated lazily when first requested."
        ( https://netbeans.apache.org/wiki/DevFaqLookupDefault ) So the SVGLoader implementation module will
        only be loaded the first time an SVG file is actually encountered for loading, rather than,
        for instance, when the startup splash screen initializes ImageUtilities to load its PNG
        image. This was confirmed by printing a debugging message from a static initializer in
        SVGLoaderImpl in the implementation module; the message appears well after the splash
        screen's PNG graphics first becomes visible. */
        return svgLoaderLoader.getLoader();
    }

    /**
     * Get the class loader from lookup.
     * Since this is done very frequently, it is wasteful to query lookup each time.
     * Instead, remember the last result and just listen for changes.
     */
    static ClassLoader getClassLoader() {
        return classLoaderLoader.getLoader();
    }

    private static final class CachedLookupLoader<T> {
        private final Class<T> clazz;
        private final AtomicBoolean noLoaderWarned = new AtomicBoolean(false);
        /**
         * Cached result of {@link #getLoader()}. Null means the cache is cleared; absent means the
         * result is cached but was null (no loader found). This field is marked volatile so that it
         * can be retrieved without synchronization in the common case, like in prior versions.
         */
        private volatile Optional<T> currentLoader;
        /**
         * The thread which last started performing an uncached lookup, when said lookup is
         * currently in progress. The result of an uncached lookup will only be cached if
         * (1) LookupListener.resultChanged was _not_ called while the lookup was being performed
         * and (2) no other uncached lookups were started more recently. This is slightly cleaned-up
         * version of the fix for an old race condition bug (#62194 in BugZilla).
         */
        private Thread threadInProgress;
        private Lookup.Result<T> loaderQuery;

        public CachedLookupLoader(Class<T> clazz) {
            Parameters.notNull("clazz", clazz);
            this.clazz = clazz;
        }

        public T getLoader() {
            Optional<T> toReturn = currentLoader;
            if (toReturn != null) {
                return toReturn.orElse(null);
            }
            final Lookup.Result<T> useLoaderQuery;
            synchronized (this) {
                // Signal to other threads that their result is outdated.
                threadInProgress = Thread.currentThread();
                if (loaderQuery == null) {
                    loaderQuery = Lookup.getDefault().lookupResult(clazz);
                    loaderQuery.addLookupListener(
                        new LookupListener() {
                            @Override
                            public void resultChanged(LookupEvent ev) {
                                LOGGER.log(Level.FINE, "Loader for {0} cleared", clazz); // NOI18N
                                /* Clear any existing cached result, and indicate to ongoing lookup
                                operations in other threads that their results are outdated and
                                should not be cahced. */
                                synchronized (CachedLookupLoader.this) {
                                    currentLoader = null;
                                    threadInProgress = null;
                                }
                            }
                        }
                    );
                }
                useLoaderQuery = loaderQuery;
            }

            Iterator<? extends T> it = useLoaderQuery.allInstances().iterator();
            toReturn = Optional.ofNullable(it.hasNext() ? it.next() : null);
            if (!toReturn.isPresent()) {
                if (!noLoaderWarned.getAndSet(true)) {
                    LOGGER.log(Level.WARNING, "No {0} instance found in {1}", // NOI18N
                            new Object[]{ clazz, Lookup.getDefault() });
                }
            } else if (LOGGER.isLoggable(Level.FINE)) {
                // Log message must start with "Loader computed", per ImageUtilitiesGetLoaderTest.
                LOGGER.log(Level.FINE, "Loader computed for {0}: {1}", // NOI18N
                        new Object[]{ clazz, toReturn.orElse(null) });
            }
            synchronized (this) {
                if (threadInProgress == Thread.currentThread()) {
                    /* We're the last thread to have started performing a lookup, and the result has
                    not been invalidated since after we started the operation, so we can safely
                    cache the result. */
                    threadInProgress = null;
                    currentLoader = toReturn;
                }
            }
            return toReturn.orElse(null);
        }
    }

    static ToolTipImage getIcon(String resource, boolean localized) {
        if (localized) {
            if (resource == null) {
                return null;
            }
            synchronized (localizedCache) {
                ActiveRef<String> ref = localizedCache.get(resource);
                ToolTipImage img = null;

                // no icon for this name (already tested)
                if (ref == NO_ICON) {
                    return null;
                }

                if (ref != null) {
                    // then it is SoftRefrence
                    img = ref.get();
                }

                // icon found
                if (img != null) {
                    return img;
                }

                // find localized or base image
                ClassLoader loader = getClassLoader();

                // we'll keep the String probably for long time, optimize it
                resource = new String(resource).intern(); // NOPMD

                String base;
                String ext;
                int idx = resource.lastIndexOf('.');

                if ((idx != -1) && (idx > resource.lastIndexOf('/'))) {
                    base = resource.substring(0, idx);
                    ext = resource.substring(idx);
                } else {
                    base = resource;
                    ext = ""; // NOI18N
                }

                // #31008. [PENDING] remove in case package cache is precomputed
                java.net.URL baseurl = (loader != null) ? loader.getResource(resource) // NOPMD
                        : ImageUtilities.class.getClassLoader().getResource(resource);
                Iterator<String> it = NbBundle.getLocalizingSuffixes();
                
                while (it.hasNext()) {
                    String suffix = it.next();
                    ToolTipImage i;

                    if (suffix.isEmpty()) {
                        i = getIcon(resource, loader, false);
                    } else {
                        i = getIcon(base + suffix + ext, loader, true);
                    }

                    if (i != null) {
                        localizedCache.put(resource, new ActiveRef<>(i, localizedCache, resource));
                        return i;
                    }
                }
                localizedCache.put(resource, NO_ICON);
                return null;
            }
        } else {
            return getIcon(resource, getClassLoader(), false);
        }
    }

    /** Finds image for given resource.
    * @param name name of the resource
    * @param loader classloader to use for locating it, or null to use classpath
    * @param localizedQuery whether the name contains some localization suffix
    *  and is not optimized/interned
    */
    private static ToolTipImage getIcon(String name, ClassLoader loader, boolean localizedQuery) {
        if (name == null) {
            return null;
        }
        ActiveRef<String> ref = cache.get(name);
        ToolTipImage img = null;

        // no icon for this name (already tested)
        if (ref == NO_ICON) {
            return null;
        }

        if (ref != null) {
            img = ref.get();
        }

        // icon found
        if (img != null) {
            return img;
        }

        synchronized (cache) {
            // again under the lock
            ref = cache.get(name);

            // no icon for this name (already tested)
            if (ref == NO_ICON) {
                return null;
            }

            if (ref != null) {
                // then it is SoftRefrence
                img = ref.get();
            }

            if (img != null) {
                // cannot be NO_ICON, since it never disappears from the map.
                return img;
            }

            // path for bug in classloader
            String n;
            boolean warn;

            if (name.startsWith("/")) { // NOI18N
                warn = true;
                n = name.substring(1);
            } else {
                warn = false;
                n = name;
            }

            // Load the icon.
            SVGLoader svgLoader = null; // If not null, url should be loaded as an SVG file.
            ClassLoader useClassLoader =
                    (loader != null) ? loader : ImageUtilities.class.getClassLoader();
            java.net.URL url = null;
            if (n.endsWith(".png") || n.endsWith(".gif") || n.endsWith(".svg")) {
                /* If an SVG version of the image is available, always load that one. Only attempt
                to load the SVGLoader implementation module if an actual SVG file exists. */
                URL svgURL = useClassLoader.getResource(n.substring(0, n.length() - 4) + ".svg");
                if (svgURL != null) {
                    svgLoader = getSVGLoader();
                    if (svgLoader != null) {
                        url = svgURL;
                    } else {
                        LOGGER.log(Level.INFO, "No SVG loader available for loading {0}", svgURL);
                    }
                }
            }
            if (url == null && !n.endsWith(".svg")) { // The SVG case was handled before.
                url = useClassLoader.getResource(n);
            }

            Image result = null;
            if (url != null) {
                if (svgLoader != null) {
                    try {
                        result = icon2ToolTipImage(svgLoader.loadIcon(url), url);
                    } catch (IOException ioe) {
                        LOGGER.log(Level.INFO, "Failed to load SVG image " + url, ioe);
                    }
                } else if (name.endsWith(".png")) {
                    try (ImageInputStream stream = ImageIO.createImageInputStream(url.openStream())) {
                        ImageReadParam param = PNG_READER.getDefaultReadParam();
                        PNG_READER.setInput(stream, true, true);
                        result = PNG_READER.read(0, param);
                    } catch (IOException ioe) {
                        LOGGER.log(Level.INFO, "Image "+name+" is not PNG", ioe);
                    }
                }
                if (result == null) {
                    try {
                        result = ImageIO.read(url);
                    } catch (IOException ioe) {
                        LOGGER.log(Level.WARNING, "Cannot load " + name + " image", ioe);
                    }
                }
            }

            if (result != null) {
                if (warn && extraInitialSlashes.add(name)) {
                    LOGGER.warning(
                        "Initial slashes in Utilities.loadImage deprecated (cf. #20072): " +
                        name
                    ); // NOI18N
                }

                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "loading icon {0} = {1}", new Object[] {n, result});
                }
                name = new String(name).intern(); // NOPMD
                ToolTipImage toolTipImage = result instanceof ToolTipImage tti
                        ? tti
                        : ToolTipImage.createNew("", result, url);
                cache.put(name, new ActiveRef<>(toolTipImage, cache, name));
                return toolTipImage;
            } else { // no icon found
                if (!localizedQuery) {
                    cache.put(name, NO_ICON);
                }
                return null;
            }
        }
    }

    private static void ensureLoaded(Image image) {
        if (
            (Toolkit.getDefaultToolkit().checkImage(image, -1, -1, null) &
                (ImageObserver.ALLBITS | ImageObserver.FRAMEBITS)) != 0
        ) {
            return;
        }

        synchronized (tracker) {
            int id = ++mediaTrackerID;
            tracker.addImage(image, id);

            try {
                tracker.waitForID(id, 0);
            } catch (InterruptedException e) {
                System.out.println("INTERRUPTED while loading Image");
            }

            // #262804 assertation disabled because of error, when using ImageFilter
            // assert (tracker.statusID(id, false) == MediaTracker.COMPLETE) : "Image loaded";
            tracker.removeImage(image, id);
        }
    }
    
    private static ToolTipImage doMergeImages(Image image1, Image image2, int x, int y) {
        ensureLoaded(image1);
        ensureLoaded(image2);

        int w = Math.max(1, Math.max(image1.getWidth(null), x + image2.getWidth(null)));
        int h = Math.max(1, Math.max(image1.getHeight(null), y + image2.getHeight(null)));
        boolean bitmask = image1 instanceof Transparency trans1 && trans1.getTransparency() != Transparency.TRANSLUCENT
                       && image2 instanceof Transparency trans2 && trans2.getTransparency() != Transparency.TRANSLUCENT;

        StringBuilder str = new StringBuilder(image1 instanceof ToolTipImage ? ((ToolTipImage)image1).toolTipText : "");
        if (image2 instanceof ToolTipImage toolTipImage) {
            String toolTip = toolTipImage.toolTipText;
            if (str.length() > 0 && toolTip.length() > 0) {
                str.append(TOOLTIP_SEPAR);
            }
            str.append(toolTip);
        }
        Object firstUrl = image1.getProperty(PROPERTY_URL, null);
        
        ColorModel model = colorModel(bitmask? Transparency.BITMASK: Transparency.TRANSLUCENT);
        // Provide a delegate Icon for scalable rendering.
        Icon delegateIcon = new MergedIcon(image2Icon(image1), image2Icon(image2), x, y);
        ToolTipImage buffImage = new ToolTipImage(str.toString(), delegateIcon,
                model, model.createCompatibleWritableRaster(w, h), model.isAlphaPremultiplied(), null, firstUrl instanceof URL ? (URL)firstUrl : null
            );

        // Also provide an Image-based rendering for backwards-compatibility.
        java.awt.Graphics g = buffImage.createGraphics();
        g.drawImage(image1, 0, 0, null);
        g.drawImage(image2, x, y, null);
        g.dispose();

        return buffImage;
    }

    /**
     * Alternative image merging implementation using the {@link Icon} API. This preserves
     * scalability of the delegate {@code Icon}s on HiDPI displays.
     */
    private static final class MergedIcon extends CachedHiDPIIcon {
        private final Icon icon1;
        private final Icon icon2;
        private final int x, y;

        public MergedIcon(Icon icon1, Icon icon2, int x, int y) {
            super(Math.max(icon1.getIconWidth(), x + icon2.getIconWidth()),
                  Math.max(icon1.getIconHeight(), y + icon2.getIconHeight()));
            this.icon1 = icon1;
            this.icon2 = icon2;
            this.x = x;
            this.y = y;
        }

        @Override
        protected Image createAndPaintImage(
                Component c, ColorModel colorModel, int deviceWidth, int deviceHeight, double scale)
        {
            BufferedImage ret = createBufferedImage(colorModel, deviceWidth, deviceHeight);
            Graphics2D g = ret.createGraphics();
            try {
                g.clip(new Rectangle(0, 0, deviceWidth, deviceHeight));
                g.scale(scale, scale);
                icon1.paintIcon(c, g, 0, 0);
                icon2.paintIcon(c, g, x, y);
            } finally {
                g.dispose();
            }
            return ret;
        }
    }

    /** Creates BufferedImage with Transparency.TRANSLUCENT */
    static final java.awt.image.BufferedImage createBufferedImage(int width, int height) {
        if (Utilities.isMac()) {
            return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        }

        ColorModel model = colorModel(java.awt.Transparency.TRANSLUCENT);
        java.awt.image.BufferedImage buffImage = new java.awt.image.BufferedImage(
                model, model.createCompatibleWritableRaster(width, height), model.isAlphaPremultiplied(), null
            );

        return buffImage;
    }
    
    private static ColorModel colorModel(int transparency) {
        ColorModel model;
        try {
            model = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getDefaultScreenDevice().getDefaultConfiguration()
                .getColorModel(transparency);
        }
        catch(ArrayIndexOutOfBoundsException aioobE) {
            //#226279
            model = ColorModel.getRGBdefault();
        } catch(HeadlessException he) {
            model = ColorModel.getRGBdefault();
        }
        return model;
    }

    /**
     * Key used for composite images -- it holds image identities
     */
    private record CompositeImageKey(Image baseImage, Image overlayImage, int x, int y) {

        @Override
        public boolean equals(Object other) {
            return other instanceof CompositeImageKey key
                    ? x == key.x && y == key.y && baseImage == key.baseImage && overlayImage == key.overlayImage
                    : false;
        }

        @Override
        public int hashCode() {
            int hash = ((x << 3) ^ y) << 4;
            return hash ^ System.identityHashCode(baseImage) ^ System.identityHashCode(overlayImage);
        }

        @Override
        public String toString() {
            return "Composite key for " + baseImage + " + " + overlayImage + " at [" + x + ", " + y + "]"; // NOI18N
        }
    }
    
    /**
     * Key used for ToolTippedImage
     */
    private record ToolTipImageKey(Image image, String str) {

        @Override
        public boolean equals(Object other) {
            return other instanceof ToolTipImageKey key
                    ? str.equals(key.str) && image == key.image
                    : false;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(image) ^ str.hashCode();
        }

        @Override
        public String toString() {
            return "ImageStringKey for " + image + " + " + str; // NOI18N
        }
    }

    /** Cleaning reference. */
    private static final class ActiveRef<T> extends SoftReference<ToolTipImage> implements Runnable {
        private final Map<T,ActiveRef<T>> holder;
        private final T key;

        public ActiveRef(ToolTipImage o, Map<T,ActiveRef<T>> holder, T key) {
            super(o, Utilities.activeReferenceQueue());
            this.holder = holder;
            this.key = key;
        }

        @Override
        public void run() {
            synchronized (holder) {
                holder.remove(key);
            }
        }
    }
     // end of ActiveRef

    /**
     * Wraps an arbitrary {@link Icon} inside an {@link ImageIcon}. This allows us to provide
     * scalable icons from {@link #loadImageIcon(String,boolean)} without changing the API.
     */
    private static final class IconImageIcon extends ImageIcon {
        /* I'd love to make this final, but the custom serialization handling precludes this. Make
        it volatile instead, to be completely sure that the class is still thread-safe. */
        private volatile Icon delegate;

        IconImageIcon(ToolTipImage delegate) {
            super(delegate);
            Parameters.notNull("delegate", delegate);
            this.delegate = delegate;
        }

        @Override
        public synchronized void paintIcon(Component c, Graphics g, int x, int y) {
            delegate.paintIcon(c, g, x, y);
        }

        public Icon getDelegateIcon() {
            return delegate;
        }

        /* NETBEANS-3769: Since ImageIcon implements Serializable, we must support serialization.
        But there is no guarantee that the delegate implements Serializable, thus the default
        serialization mechanism might throw a java.io.NotSerializableException when
        ObjectOutputStream.writeObject gets recursively called on the delegate. Implement a custom
        serialization mechanism based on ImageIcon instead. */

        @Serial
        private void writeObject(ObjectOutputStream out) throws IOException {
            out.writeObject(new ImageIcon(getImage()));
        }

        @Serial
        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            this.delegate = (ImageIcon) in.readObject();
        }

        @Serial
        private void readObjectNoData() throws ObjectStreamException {
            this.delegate = new ImageIcon(new BufferedImage(1, 1, BufferedImage.TYPE_4BYTE_ABGR));
        }
    }

    /**
     * Image with tool tip text (for icons with badges).
     *
     * <p>On MacOS, HiDPI (Retina) support in JMenuItem.setIcon(Icon) requires the Icon argument to
     * be an instance of ImageIcon wrapping a MultiResolutionImage (see
     * com.apple.laf.ScreenMenuIcon.setIcon, com.apple.laf.AquaIcon.getImageForIcon, and
     * sun.lwawt.macosx.CImage.Creator.createFromImage). Thus we have this class implement
     * MultiResolutionImage, and use asImageIcon when needed via asImageIconIfRequiredForRetina.
     */
    private static class ToolTipImage extends BufferedImage implements Icon, MultiResolutionImage {
        final String toolTipText;
        // May be null.
        final Icon delegateIcon;
        // May be null.
        final URL url;
        // May be null.
        ImageIcon imageIconVersion;
        // May be null.
        volatile BufferedImage doubleSizeVariant;

        public static ToolTipImage createNew(String toolTipText, Image image, URL url) {
            ImageUtilities.ensureLoaded(image);
            boolean bitmask = image instanceof Transparency trans && trans.getTransparency() != Transparency.TRANSLUCENT;
            ColorModel model = colorModel(bitmask ? Transparency.BITMASK : Transparency.TRANSLUCENT);
            int w = Math.max(1, image.getWidth(null));
            int h = Math.max(1, image.getHeight(null));
            if (url == null) {
                Object value = image.getProperty(PROPERTY_URL, null);
                url = value instanceof URL u ? u : null;
            }
            Icon icon = (image instanceof ToolTipImage)
                   ? ((ToolTipImage) image).getDelegateIcon() : null;
            ToolTipImage newImage = new ToolTipImage(
                toolTipText,
                icon,
                model,
                model.createCompatibleWritableRaster(w, h),
                model.isAlphaPremultiplied(), null, url
            );

            java.awt.Graphics g = newImage.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
            return newImage;
        }

        public ToolTipImage(
            String toolTipText, Icon delegateIcon, ColorModel cm, WritableRaster raster,
            boolean isRasterPremultiplied, Hashtable<?, ?> properties, URL url
        ) {
            super(cm, raster, isRasterPremultiplied, properties);
            this.toolTipText = toolTipText;
            this.delegateIcon = delegateIcon;
            this.url = url;
        }

        public synchronized ImageIcon asImageIcon() {
            if (imageIconVersion == null) {
                imageIconVersion = new IconImageIcon(this);
            }
            return imageIconVersion;
        }

        public Icon asImageIconIfRequiredForRetina() {
            /* We could choose to do this only on MacOS, but doing it on all platforms will lower
            the chance of undetected platform-specific bugs. */
            return delegateIcon != null ? asImageIcon() : this;
        }

        /**
         * @param url may be null
         */
        public ToolTipImage(Icon delegateIcon, String toolTipText, URL url, int imageType) {
            // BufferedImage must have width/height > 0.
            super(Math.max(1, delegateIcon.getIconWidth()),
                    Math.max(1, delegateIcon.getIconHeight()), imageType);
            this.delegateIcon = delegateIcon;
            this.toolTipText = toolTipText;
            this.url = url;
        }

        /**
         * Get an {@link Icon} instance representing a scalable version of this {@code Image}.
         *
         * @return may be null
         */
        public Icon getDelegateIcon() {
            return delegateIcon;
        }

        @Override
        public int getIconHeight() {
            return super.getHeight();
        }

        @Override
        public int getIconWidth() {
            return super.getWidth();
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (delegateIcon != null) {
                delegateIcon.paintIcon(c, g, x, y);
            } else {
                /* There is no scalable delegate icon available. On HiDPI displays, this means that
                original low-resolution icons will need to be scaled up to a higher resolution. Do a
                few tricks here to improve the quality of the scaling. See NETBEANS-2614 and the
                before/after screenshots that are attached to said JIRA ticket. */
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    final AffineTransform tx = g2.getTransform();
                    final int txType = tx.getType();
                    final double scale;
                    if (txType == AffineTransform.TYPE_UNIFORM_SCALE ||
                        txType == (AffineTransform.TYPE_UNIFORM_SCALE | AffineTransform.TYPE_TRANSLATION))
                    {
                      scale = tx.getScaleX();
                    } else {
                      scale = 1.0;
                    }
                    if (scale != 1.0) {
                        /* The default interpolation mode is nearest neighbor. Use bicubic
                        interpolation instead, which looks better, especially with non-integral
                        HiDPI scaling factors (e.g. 150%). Even for an integral 2x scaling factor
                        (used by all Retina displays on MacOS), the blurred appearance of bicubic
                        scaling ends up looking better on HiDPI displays than the blocky appearance
                        of nearest neighbor. */
                        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        g2.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
                        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                        /* For non-integral scaling factors, we frequently encounter non-integral
                        device pixel positions. For instance, with a 150% scaling factor, the
                        logical pixel position (7,0) would map to device pixel position (10.5,0).
                        On such scaling factors, icons look a lot better if we round the (x,y)
                        translation to an integral number of device pixels before painting. */
                        g2.setTransform(new AffineTransform(scale, 0, 0, scale,
                                (int) tx.getTranslateX(), (int) tx.getTranslateY()));
                    }
                    g2.drawImage(this, x, y, null);
                } finally {
                    g2.dispose();
                }
            }
        }

        @Override
        public Object getProperty(String name, ImageObserver observer) {
            if (PROPERTY_URL.equals(name)) { // NOI18N
                /* In some cases it might strictly be more appropriate to return
                Image.UndefinedProperty rather than null (see Javadoc spec for this method), but
                retain the existing behavior and use null instead here. That way there won't be a
                ClassCastException if someone tries to cast to URL. */
                if (url != null) {
                    return url;
                } else if (!(delegateIcon instanceof ImageIcon)) {
                    return null;
                } else {
                    Image image = ((ImageIcon) delegateIcon).getImage();
                    if (image == this || image == null) {
                        return null;
                    }
                    return image.getProperty(name, observer);
                }
            }
            return super.getProperty(name, observer);
        }

        private Image getDoubleSizeVariant() {
          if (delegateIcon == null) {
              return null;
          }
          BufferedImage ret = doubleSizeVariant;
          if (ret == null) {
              int SCALE = 2;
              ColorModel model = getColorModel();
              int w = delegateIcon.getIconWidth()  * SCALE;
              int h = delegateIcon.getIconHeight() * SCALE;
              ret = new BufferedImage(
                    model,
                    model.createCompatibleWritableRaster(w, h),
                    model.isAlphaPremultiplied(), null);
              Graphics g = ret.createGraphics();
              try {
                  ((Graphics2D) g).transform(AffineTransform.getScaleInstance(SCALE, SCALE));
                  delegateIcon.paintIcon(dummyIconComponentLabel, g, 0, 0);
              } finally {
                  g.dispose();
              }
              doubleSizeVariant = ret;
          }
          return ret;
        }

        @Override
        public Image getResolutionVariant(double destImageWidth, double destImageHeight) {
            if (destImageWidth <= getWidth(null) && destImageHeight <= getHeight(null)) {
                /* Returning "this" should be safe here, as the same is done in
                sun.awt.image.MultiResolutionToolkitImage. */
                return this;
            }
            Image ds = getDoubleSizeVariant();
            return ds != null ? ds : this;
        }

        @Override
        public List<Image> getResolutionVariants() {
            Image ds = getDoubleSizeVariant();
            return ds == null ? List.of(this) : List.of(this, ds);
        }
    }

    private static final class DisabledButtonFilter extends RGBImageFilter {
        public static final RGBImageFilter INSTANCE_LIGHT = new DisabledButtonFilter(false);
        public static final RGBImageFilter INSTANCE_DARK  = new DisabledButtonFilter(true);
        private final int baseGray;

        DisabledButtonFilter(boolean dark) {
            canFilterIndexColorModel = true;
            baseGray = dark ? 0x444444 : 0x888888;
        }

        @Override
        public int filterRGB(int x, int y, int argb) {
            return
                // Keep the alpha channel unmodified.
                (argb & 0xff000000) +
                // Reduce the color bandwidth by a quarter (>> 2), and mix with gray.
                baseGray +
                ((((argb >> 16) & 0xff) >> 2) << 16) +
                ((((argb >> 8 ) & 0xff) >> 2) <<  8) +
                ((((argb      ) & 0xff) >> 2)      );
        }

        // override the superclass behaviour to not pollute
        // the heap with useless properties strings. Saves tens of KBs
        @Override
        public void setProperties(Hashtable<?,?> props) {
            consumer.setProperties((Hashtable<?,?>) props.clone());
        }
    }
}
