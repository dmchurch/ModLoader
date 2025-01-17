package de.dakror.modding;

import java.io.File;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import de.dakror.modding.platform.IModLoader;
import de.dakror.modding.platform.IModPlatform;

/**
 * The following description is out-of-date. First, ModLoader isn't itself a ClassLoader, because of
 * chicken-and-egg problems; the actual ClassLoader responsible is an instance of {@link ModClassLoader},
 * which by necessity lives in its own separate package (Java doesn't like it when classes of a given
 * package are loaded by two separate ClassLoaders) and delegates to an instance of ModLoader for the
 * actual "modding" part of the process. ModLoader itself is a base class that provides the interface,
 * while the current implementation lives in {@link de.dakror.modding.asm.ASMModLoader}. There's no reason
 * other implementations couldn't be explored, for example to experiment with other bytecode-manipulation
 * libraries than ASM.
 * <p>
 * The other thing that makes the below explanation out-of-date is that the split-horizon ClassLoader
 * implementation described simply doesn't work. The Java docs claim that the VM can cope with multiple
 * class definitions for the same class as long as each definition was loaded by a different ClassLoader,
 * which is true in a broader sense, but what isn't mentioned is that two different definitions of
 * the same classname can't interact with each other, and they certainly can't inherit from each other.
 * This means that performing class augmentation requires bytecode modification and not just loader
 * tricks; this functionality is currently extracted to the {@link ClassAugmentationBase} class, which
 * itself is under construction and has a couple different implementations as well; see the comments
 * for that class, whenever I write them.
 * <p>
 * With that caveat, the original class description follows until such time as I write a better explanation:
 * <hr>
 * ModLoader is a {@link ClassLoader} that allows for patching the definitions of classes and resources
 * on-the-fly. On the surface, it's a {@link URLClassLoader} that adds all discovered mod JARs (or
 * class hierarchies) to the classpath; all code thus has access to every package defined in every
 * mod, so that mod JARs can reference code from other JARs. The ModLoader is effectively designed to
 * replace the standard application ClassLoader (the one which implements the classpath specified from
 * the command line/launcher); all application-specific classes will be defined and resolved by the
 * ModLoader. The ModLoader inherits from the application loader's parent, the <i>system</i> classloader
 * (responsible for locating and loading all framework-provided classes).
 * <p>
 * The ModLoader keeps a reference around to the original application ClassLoader for the purposes
 * of fetching resources and class-file bytestreams, but it does not allow the application loader to
 * instantiate Class objects; if it did, code from those classes would use the app loader to resolve
 * class name references, rather than the ModLoader itself.
 * <p>
 * The patching functionality works by selectively replacing class definitions (or resources) with
 * alternative implementations <i>when they are first requested</i>. This limit is forced by the JVM,
 * which (quite reasonably) caches class definitions after first use; the only way around this is to use
 * the Java Agent protocol, which is a significantly heavier-weight undertaking.
 * <p>
 * The simplest form of class-patching involves replacing a stock class with a complete replacement,
 * perhaps after extracting and tweaking the original code. For example, if the
 * <code>de.dakror.quarry.Quarry</code> class were to be replaced by a mod-provided class
 * <code>com.example.quarrymod.AltQuarry</code> that includes all the same functionality, then the
 * ModLoader will, on being asked for the definition of a class called <code>de.dakror.quarry.Quarry</code>,
 * simply return the AltQuarry definition instead. The class-loading hierarchy thus looks as follows:
 * <p>
 * <pre>
 * Loader            Request                  Class definition
 *              |
 * [ModLoader]  | "d.d.q.d.DesktopLauncher" → [d.d.q.d.DesktopLauncher]
 *              |                             (references d.d.q.Quarry)
 *              | "d.d.q.Quarry"            → [c.e.q.AltQuarry]
 *      ↓       |                             (references j.l.String)
 *              | "j.l.String" ↓
 * [sys loader] |     "j.l.String"          → [j.l.String]
 * </pre>
 * <p>
 * This is not the most convenient way to implement modding, however, since it requires that any
 * class to be patched must include all the original code of that class in the mod (and thus also
 * precludes combining different mods that want to patch the same class). More convenient, then,
 * is if the replacement class can inherit from the original class; polymorphism dictates that the
 * replacement will be a proper functional replacement for the original. This is more difficult,
 * however, since now we have to implement a "split-horizon" name resolution scheme; since the
 * replacement must reference the original class in its <code>extends</code> clause, its own
 * resolution of the original name must remain untampered, while all <i>other</i> code must
 * reference the replacement class.
 * <p>
 * This is implemented using one-off {@link ModLoader.SubLoader} instances, where each SubLoader
 * is responsible for loading and defining one and only one class file. Within the context of the
 * SubLoader, references to the original class name are resolved to the original class definition;
 * everywhere else, they resolve to the replacement. Using the same example as above, this time
 * with the <code>AltQuarry</code> class inheriting from <code>Quarry</code>:
 * <p>
 * <pre>
 * Loader               Request                      Class definition
 *                 |
 * [ModLoader]     | "d.d.q.d.DesktopLauncher"    → [d.d.q.d.DesktopLauncher]
 * |               |                                (references d.d.q.Quarry)
 * |               | "d.d.q.Quarry" ↓
 * | [SubLoader 1] |     "c.e.q.AltQuarry"        → [c.e.q.AltQuarry]
 * |               |                                (references d.d.q.Quarry)
 * |      ↓        | "d.d.q.Quarry"               → [d.d.q.Quarry]
 * |               |                                (references j.l.String)
 * |               | "j.l.String" ↓
 * [ModLoader]     |     "j.l.String" ↓
 * [sys loader]    |         "j.l.String"         → [j.l.String]
 * </pre>
 */

abstract public class ModLoader implements IModLoader, ModAPI {
    public static final String MODLOADER_IMPL = System.getProperty("de.dakror.modding.impl", "de.dakror.modding.asm.ASMModLoader");
    protected IModPlatform modPlatform;
    protected URL[] modUrls;
    protected List<IBaseMod> mods = new ArrayList<>();
    protected List<IClassMod<?,?>> classMods = new ArrayList<>();
    protected List<IResourceMod> resourceMods = new ArrayList<>();

    public static IModLoader newInstance(IModPlatform modPlatform, String[] args) throws ClassNotFoundException, ClassCastException {
        Class<?> modLoaderClass = Class.forName(MODLOADER_IMPL);

        assert modLoaderClass != ModLoader.class; // avoid this infinite loop, let's do
        try {
            // Try delegating to the defined loader's static newInstance method, if it exists. If not we'll just try direct instantiation.
            Method newInstanceMethod = modLoaderClass.getDeclaredMethod("newInstance", IModPlatform.class, String[].class);

            // double-check that we're not recursing ourselves
            if (!newInstanceMethod.equals(ModLoader.class.getDeclaredMethod("newInstance")))  {
                return (IModLoader) newInstanceMethod.invoke(null, modPlatform, args);
            }
        } catch (NoSuchMethodException|IllegalAccessException|IllegalArgumentException ignore) {
        } catch (InvocationTargetException ite) {
            // We might care about this. Spit out diagnostics before continuing.
            ModAPI.DEBUGLN("Exception while executing %s.newInstance(%s, %s): %s", MODLOADER_IMPL, modPlatform, Arrays.toString(args), ite.getTargetException());
        }

        // No static newInstance() on the target class. That's fine, we'll just try instantiating it ourselves.
        try {
            return modLoaderClass.asSubclass(IModLoader.class).getConstructor().newInstance();
        } catch (NoSuchMethodException|InstantiationException|IllegalAccessException e) {
            throw new UnsupportedOperationException(String.format("%s.newInstance() failed to create implementation by new %s()", ModLoader.class.getName(), MODLOADER_IMPL), e);
        } catch (InvocationTargetException ite) {
            var e = ite.getTargetException();
            throw new RuntimeException(String.format("While instantiating implementation, new %s() threw exception: %s", MODLOADER_IMPL, e.getMessage()), e);
        }
    }

    @Override
    public ModLoader init(IModPlatform modPlatform, ClassLoader appLoader, String[] args) {
        this.modPlatform = modPlatform;
        this.modUrls = findMods();

        modPlatform.addModURLs(modUrls);

        implInit();
        getMod(IModScanner.class).scanForMods(this);
        return this;
    }

    public List<URL> getModUrls() {
        return List.of(modUrls);
    }

    protected URL[] findMods() {
        debugln("finding mods");
        List<URL> mods = new ArrayList<URL>();
        URL modloaderLocation = ModLoader.class.getProtectionDomain().getCodeSource().getLocation();
        mods.add(modloaderLocation);
        try {
            String testMod = System.getProperty("de.dakror.modding.testmod");
            if (testMod != null) {
                mods.add(modloaderLocation.toURI().resolve(testMod).toURL());
            }
            File modDir = new File("./mods");
            if (modDir.isDirectory()) {
                PathMatcher jarMatcher = FileSystems.getDefault().getPathMatcher("glob:**.jar");
                try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(modDir.toPath())) {
                    for (Path entry: dirStream) {
                        if (Files.isDirectory(entry) || (jarMatcher.matches(entry) && Files.isReadable(entry))) {
                            debugln("adding: "+entry);
                            mods.add(entry.normalize().toUri().toURL());
                        }
                    }
                }
            }
        } catch (Exception e) {
            debugln("Unhandled exception while finding mods, ignoring: "+e);
        }
        return mods.toArray(URL[]::new);
    }

    abstract protected void implInit();

    public void replaceClass(String replacedClass, String replacementClass) {
        getMod(IClassReplacement.class).replaceClass(replacedClass, replacementClass);
    }

    public void augmentClass(String augmentedClass, String augmentationClass) {
        getMod(IClassAugmentation.class).augmentClass(augmentedClass, augmentationClass);
    }

    public ClassLoader getClassLoader() {
        return modPlatform.getClassLoader();
    }

    public void registerMod(String modClassname) throws ClassNotFoundException, ClassCastException {
        Class<?> rawClass = modPlatform.loadClass(modClassname);
        Class<? extends IBaseMod> modClass = null;
        try {
            modClass = rawClass.asSubclass(IBaseMod.class);
        } catch (ClassCastException e) {}

        var constructorArgs = new Object[][] {
            {this},
            {this},
            {},
        };
        var constructorArgTypes = new Class<?>[][] {
            {getClass()},
            {ModLoader.class},
            {}
        };

        IBaseMod mod = null;
        // by "constructor" we either mean a constructor or a public static newInstance(...) method
        for (int i = 0; i < constructorArgs.length*2; i++) {
            var args = constructorArgs[i/2];
            var argTypes = constructorArgTypes[i/2];
            try {
                if (i % 2 == 0) {
                    // try as a method
                    mod = (IBaseMod)rawClass.getMethod("newInstance", argTypes).invoke(null, args);
                } else if (modClass != null) {
                    // try as a constructor, but only if this is an IBaseMod
                    mod = modClass.getConstructor(argTypes).newInstance(args);
                } else {
                    // not an IBaseMod class, try only with static methods
                    continue;
                }
            } catch (NoSuchMethodException|IllegalAccessException|InstantiationException|NullPointerException e) {
                // this signature doesn't work, on to the next
                continue;
            } catch (InvocationTargetException e) {
                debugln("Constructor signature "+modClassname+(i % 2 == 0 ? ".newInstance" : "")+"("+argTypes+") threw exception: "+e.getTargetException().getMessage());
                continue;
            } catch (ClassCastException e) {
                debugln("Constructor");
            }
            // if we got here it worked
            break;
        }
        if (mod == null) {
            throw new ClassNotFoundException("No valid constructor signatures for "+modClassname);
        }
        registerMod(mod);
    }

    public <T extends IBaseMod> T registerMod(T mod) {
        try {
            mod.registered(this);
        } catch (Exception e) {
            debugln("Mod %s threw exception during registration, ignoring: %s", mod, e);
        }
        mods.add(mod);
        if (mod instanceof IResourceMod) {
            resourceMods.add((IResourceMod)mod);
        }
        if (mod instanceof IClassMod<?,?>) {
            try {
                registerClassMod((IClassMod<?,?>)mod);
            } catch (ClassCastException e) {
                debugln("Skipping registration of "+mod.toString()+" (not supported by this ModLoader?): "+e.getMessage());
            }
        }
        return mod;
    }

    protected void registerClassMod(IClassMod<?,?> mod) {
        classMods.add(mod);
    }

    public IBaseMod getMod(String className) {
        for (var mod: mods) {
            if (mod.getClass().getName().equals(className)) {
                return mod;
            }
        }
        return null;
    }

    public <T> T getMod(Class<T> modClass) {
        return getMod(modClass, false);
    }

    public <T> T getMod(Class<T> modClass, boolean exact) {
        for (var mod: mods) {
            if (modClass.isInstance(mod) && (mod.getClass() == modClass || !exact)) {
                return modClass.cast(mod);
            }
        }
        return null;
    }

    public IModScanner getScanner() {
        return getMod(IModScanner.class);
    }

    public boolean resourceHooked(String name) {
        for (var mod: resourceMods) {
            if (mod.hooksResource(name)) {
                return true;
            }
        }
        return false;
    }

    public boolean classHooked(String className) {
        for (var mod: classMods) {
            if (mod.hooksClass(className)) {
                return true;
            }
        }
        return false;
    }

    public InputStream redefineResourceStream(String name, InputStream stream) {
        for (var mod: resourceMods) {
            if (!mod.hooksResource(name)) {
                continue;
            }
            var newStream = mod.redefineResourceStream(name, stream, modPlatform.getClassLoader());
            if (newStream != null) {
                stream = newStream;
            }
        }
        return stream;
    }

    protected <T, C> T applyMods(List<IClassMod<T, C>> classMods, String name, T classDef, C context) throws ClassNotFoundException {
        for (var mod: classMods) {
            if (mod.hooksClass(name)) {
                try {
                    var newDef = mod.redefineClass(name, classDef, context);
                    if (newDef != null) {
                        classDef = newDef;
                    }
                } catch (NullPointerException e) {
                    if (classDef == null) {
                        // this is reasonable, just skip
                    } else {
                        throw e;
                    }
                }
            }
        }
        if (classDef == null) {
            throw new RuntimeException("did not get a classDef after mods, despite being hooked");
        }
        return classDef;
    }

    abstract public byte[] redefineClass(String name) throws ClassNotFoundException;

    public void start(String mainClass, String[] args) throws Exception {
        var patcher = new Patcher(this);
        debugln("patching classes");
        patcher.patchClasses();
        debugln("patching resources");
        patcher.patchResources();
        debugln("patching enums");
        Patcher.patchEnums();
        if (mainClass != null) {
            debugln("loading main class");
            var cls = modPlatform.loadClass(mainClass); // not loadClass, we want to be sure our classloader is loading it
            debugln("calling main()");
            cls.getMethod("main", String[].class).invoke(null, (Object) args);
        }
    }

    ///////////// INTERFACES ////////////

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    public static @interface Enabled {
        /**
         * Loading order for mods, in increasing order (ties are indeterminate).
         * ClassReplacement loads at -1000, ClassAugmentation loads at -100.
         */
        int value() default 0;
    }
    public static interface IBaseMod extends ModAPI {
        default void registered(ModLoader modLoader) throws Exception { }
    }
    public static interface IClassMod<T, C> extends IBaseMod {
        boolean hooksClass(String className);
        T redefineClass(String className, T classDef, C context) throws ClassNotFoundException;
        static <MCT, CDT, CT> boolean accepts(Class<MCT> modClassType, Class<CDT> classDefType, Class<CT> contextType, Map<TypeVariable<?>, Type> typeParams) {
        //   try (var ctx = DCONTEXT("IClassMod.accepts(%s, %s, %s, %s)", modClassType.getName(), classDefType.getSimpleName(), contextType.getSimpleName(), typeParams)) {
            if (typeParams == null) {
                typeParams = Map.of();
            }
            for (Type ifaceType: modClassType.getGenericInterfaces()) {
                // DEBUGLN("iface on %s: %s", modClassType.toGenericString(), ifaceType);
                if (ifaceType instanceof ParameterizedType) {
                    var ifacePType = (ParameterizedType) ifaceType;
                    if (ifacePType.getRawType() != IClassMod.class) {
                        continue;
                    }
                    var ifaceTArgs = ifacePType.getActualTypeArguments();
                    Type ifaceCDT = typeParams.getOrDefault(ifaceTArgs[0], ifaceTArgs[0]);
                    Type ifaceCT = typeParams.getOrDefault(ifaceTArgs[1], ifaceTArgs[1]);
                    // DEBUGLN("iface %s type args: %s, %s", ifacePType, ifaceCDT, ifaceCT);
                    if (ifaceCDT == classDefType && ifaceCT == contextType) {
                        return true;
                    }
                }
            }
            List<Type> superTypes = new ArrayList<>();
            superTypes.add(modClassType.getGenericSuperclass());
            superTypes.addAll(Arrays.asList(modClassType.getGenericInterfaces()));
            for (Type superType: superTypes) {
                if (superType == null || superType == Object.class) {
                    continue;
                }
                if (superType instanceof Class<?>) {
                    if (accepts((Class<?>)superType, classDefType, contextType, null)) {
                        return true;
                    }
                } else if (superType instanceof ParameterizedType) {
                    var superPType = (ParameterizedType) superType;
                    Class<?> superClass = (Class<?>)superPType.getRawType();
                    TypeVariable<? extends Class<?>>[] superTVars = superClass.getTypeParameters();
                    Type[] superTArgs = superPType.getActualTypeArguments();
                    Map<TypeVariable<?>, Type> superParams = new HashMap<>();

                    assert superTVars.length == superTArgs.length;
                    for (int i = 0; i < superTVars.length; i++) {
                        superParams.put(superTVars[i], typeParams.getOrDefault(superTArgs[i], superTArgs[i]));
                    }

                    if (accepts(superClass, classDefType, contextType, superParams)) {
                        return true;
                    }
                }
            }
            return false;
        //   }
        }
        default boolean accepts(Class<?> classDefType, Class<?> contextType) {
            return accepts(this.getClass(), classDefType, contextType, null);
        }

        default <U, D> IClassMod<U, D> asType(Class<U> classDefType, Class<D> contextType) throws ClassCastException {
            if (accepts(classDefType, contextType)) {
                @SuppressWarnings("unchecked") var modThis = (IClassMod<U, D>)this;
                return modThis;
            } else {
                var thisClass = this.getClass();
                for (var subClass: thisClass.getDeclaredClasses()) {
                    if (And.class.isAssignableFrom(subClass) && accepts(subClass, classDefType, contextType, null)) {
                        try {
                            @SuppressWarnings("unchecked") var subMod = (IClassMod.And<U,D>)subClass.getDeclaredConstructor(thisClass).newInstance(this);
                            And.andToBase.put(subMod, this);
                            return subMod;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
            throw new ClassCastException("Class mod "+getClass().getName()+" does not accept ("+classDefType.getName()+", "+contextType.getName()+")");
        }

        public static interface And<U, D> extends IClassMod<U, D> {
            static Map<And<?,?>, IClassMod<?, ?>> andToBase = new WeakHashMap<>();
            default boolean hooksClass(String className) {
                return andToBase.get(this).hooksClass(className);
            }
        }
    }
    public static interface IResourceMod extends IBaseMod {
        boolean hooksResource(String resourceName);
        InputStream redefineResourceStream(String resourceName, InputStream stream, ClassLoader loader);
    }

    static interface IClassReplacement {
        void replaceClass(String replacedClass, String replacementClass);
    }

    static interface IClassAugmentation {
        void augmentClass(String augmentedClass, String augmentationClass);
    }
}