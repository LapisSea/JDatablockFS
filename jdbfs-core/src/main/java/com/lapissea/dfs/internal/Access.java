package com.lapissea.dfs.internal;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.access.AccessConsent;
import com.lapissea.dfs.exceptions.MissingConstructor;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.iterableplus.Iters;
import com.lapissea.util.NotNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public final class Access{
	public enum Mode{
		PUBLIC(MethodHandles.Lookup.PUBLIC),
		MODULE(MethodHandles.Lookup.MODULE),
		PACKAGE(MethodHandles.Lookup.PACKAGE),
		PROTECTED(MethodHandles.Lookup.PROTECTED),
		PRIVATE(MethodHandles.Lookup.PRIVATE),
		ORIGINAL(MethodHandles.Lookup.ORIGINAL),
		UNCONDITIONAL(MethodHandles.Lookup.UNCONDITIONAL),
		;
		
		final int flag;
		Mode(int flag){ this.flag = flag; }
	}
	
	private static final AccessProvider PUBLIC_ACCESS      = new AccessProvider.DirectLookup(MethodHandles.publicLookup());
	private static final AccessProvider UNOPTIMIZED_ACCESS = new AccessProvider.UnoptimizedAccessProvider();
	
	private static final class LookupBox{
		private volatile MethodHandles.Lookup lookup;
	}
	
	private static final ClassValue<LookupBox> TARGET_CACHE = new ClassValue<>(){
		@Override
		protected LookupBox computeValue(Class<?> type){ return new LookupBox(); }
	};
	
	private static final class WeakModuleKey extends WeakReference<Module>{
		private final int hash;
		WeakModuleKey(Module module, ReferenceQueue<Module> queue){
			super(module, queue);
			hash = System.identityHashCode(module);
		}
		@Override
		public int hashCode(){ return hash; }
		@Override
		public boolean equals(Object o){
			if(this == o) return true;
			if(!(o instanceof WeakModuleKey other)) return false;
			var module = get();
			return module != null && module == other.get();
		}
	}
	
	private static final ReferenceQueue<Module>                                  MODULE_QUEUE = new ReferenceQueue<>();
	private static final Map<WeakModuleKey, WeakReference<MethodHandles.Lookup>> MODULE_INDEX = new ConcurrentHashMap<>();
	
	// Modules already probed for a consent anchor, weak so it dies with the module.
	private static final Set<Module> DISCOVERY_TRIED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	private static final Object      DISCOVERY_LOCK  = new Object();
	
	private static final String CONSENT_SIMPLE_NAME = "DFSAccessConsent";
	
	public static void addLookup(MethodHandles.Lookup lookup){
		try{
			AccessUtils.requireModes(lookup, Mode.PRIVATE, Mode.MODULE);
		}catch(IllegalAccessException e){
			throw new IllegalArgumentException(e);
		}
		var clazz = lookup.lookupClass();
		TARGET_CACHE.get(clazz).lookup = lookup;
		purgeModules();
		MODULE_INDEX.put(new WeakModuleKey(clazz.getModule(), MODULE_QUEUE), new WeakReference<>(lookup));
	}
	
	/**
	 * Triggers a known consent anchor, eg: from a bootstrapper that knows the modules it will access.
	 *
	 * @param anchor a class whose initializer consents its module, must declare no fields or methods
	 * @throws IllegalArgumentException when the anchor exposes members
	 */
	public static void consentModule(Class<?> anchor){
		if(anchor == null || moduleConsent(anchor.getModule()) != null){
			return;
		}
		if(!isSafeConsentAnchor(anchor)){
			throw new IllegalArgumentException(Log.fmt("{}#red is not a valid consent anchor! It must declare no fields or methods so the full-access lookup cannot leak", anchor));
		}
		
		Utils.ensureClassLoaded(anchor);
		if(moduleConsent(anchor.getModule()) == null){
			ensureModuleConsent(anchor);
		}
	}
	
	private static void purgeModules(){
		Object dead;
		while((dead = MODULE_QUEUE.poll()) != null){
			MODULE_INDEX.remove((WeakModuleKey)dead);
		}
	}
	
	private static MethodHandles.Lookup moduleConsent(Module module){
		purgeModules();
		var ref = MODULE_INDEX.get(new WeakModuleKey(module, null));
		return ref == null? null : ref.get();
	}
	
	/**
	 * @return a lookup whose lookupClass is target, or null when its module has no consent
	 */
	private static MethodHandles.Lookup resolveLookup(Class<?> target){
		if(target == null || target.isArray() || target.isPrimitive()) return null;
		
		var cache  = TARGET_CACHE.get(target);
		var cached = cache.lookup;
		if(cached != null) return cached;
		
		var consent = moduleConsent(target.getModule());
		if(consent == null) return null;
		
		MethodHandles.Lookup resolved;
		try{
			resolved = consent.lookupClass() == target? consent : MethodHandles.privateLookupIn(target, consent);
		}catch(IllegalAccessException e){
			return null;
		}
		cache.lookup = resolved;
		return resolved;
	}
	
	private static boolean ensureModuleConsent(Class<?> target){
		var module = target.getModule();
		if(moduleConsent(module) != null){
			return true;
		}
		synchronized(DISCOVERY_LOCK){
			if(moduleConsent(module) != null){
				return true;
			}
			if(!DISCOVERY_TRIED.add(module)){
				return false;
			}
			discoverDeclared(target);
			if(moduleConsent(module) == null){
				discoverConvention(target);
			}
			return moduleConsent(module) != null;
		}
	}
	
	private static void discoverDeclared(Class<?> target){
		var layer = target.getModule().getLayer();
		if(layer == null) return;
		try{
			for(var provider : ServiceLoader.load(layer, AccessConsent.class).stream().toList()){
				if(provider.type().getModule() != target.getModule()) continue;
				if(!isSafeConsentAnchor(provider.type())){
					Log.warn("Ignoring consent anchor {}#red: it must declare no fields or methods so the full-access lookup cannot leak", provider.type());
					continue;
				}
				provider.get();
				if(moduleConsent(target.getModule()) != null) return;
			}
		}catch(Throwable ignore){ }
	}
	
	private static void discoverConvention(Class<?> target){
		var     module   = target.getModule();
		var     loader   = target.getClassLoader();
		boolean named    = module.isNamed();
		var     packages = named? module.getPackages() : null;
		if(loader == null && !named) return;
		var pkg = target.getPackageName();
		while(true){
			if(!named || packages.contains(pkg)){
				var name = pkg.isEmpty()? CONSENT_SIMPLE_NAME : pkg + "." + CONSENT_SIMPLE_NAME;
				if(tryConsent(name, loader, module)) return;
			}
			if(pkg.isEmpty()) return;
			var dot = pkg.lastIndexOf('.');
			pkg = dot<0? "" : pkg.substring(0, dot);
		}
	}
	private static boolean tryConsent(String name, ClassLoader loader, Module module){
		try{
			var probe = Class.forName(name, false, loader);
			if(probe.getModule() != module) return false;
			if(!isSafeConsentAnchor(probe)){
				Log.warn("{#redWARNING:#} Ignoring consent anchor {}#red: it must declare no fields or methods so the full-access lookup cannot leak", probe);
				return false;
			}
			Class.forName(name, true, loader);
			return moduleConsent(module) != null;
		}catch(ClassNotFoundException|LinkageError e){
			return false;
		}
	}
	
	private static boolean isSafeConsentAnchor(Class<?> anchor){
		return anchor.getDeclaredFields().length == 0 && anchor.getDeclaredMethods().length == 0;
	}
	private static Class<?> targetClass(Object value){
		return switch(value){
			case Class<?> cl -> cl;
			case Method v -> v.getDeclaringClass();
			case Field v -> v.getDeclaringClass();
			case Constructor<?> v -> v.getDeclaringClass();
			default -> null;
		};
	}
	
	public static <FInter, T extends FInter> T makeLambda(Class<?> type, String name, Class<FInter> functionalInterface) throws IllegalAccessException{
		var match = Iters.from(type.getMethods()).filter(m -> m.getName().equals(name)).limit(2).toModList();
		if(match.isEmpty()){
			match = Iters.from(type.getDeclaredMethods()).filter(m -> m.getName().equals(name)).limit(2).toModList();
		}
		if(match.size()>1) throw new IllegalArgumentException("Ambiguous method name");
		return makeLambda(match.getFirst(), functionalInterface);
	}
	
	public static <FInter, T extends FInter> T makeLambda(Method method, Class<FInter> functionalInterface) throws IllegalAccessException{
		return makeLambda(method, functionalInterface, true);
	}
	public static <FInter, T extends FInter> T makeLambda(Method method, Class<FInter> functionalInterface, boolean optimized) throws IllegalAccessException{
		return access(AccessProvider::makeLambda, method, functionalInterface, "failed to create lambda\n  Method: {}#red", optimized);
	}
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, Class<FInter> functionalInterface) throws IllegalAccessException{
		return makeLambda(constructor, functionalInterface, true);
	}
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, Class<FInter> functionalInterface, boolean optimized) throws IllegalAccessException{
		return access(AccessProvider::makeLambda, constructor, functionalInterface, "failed to create lambda\n  Constructor: {}#red", optimized);
	}
	
	public static <FInter, T extends FInter> T makeLambda(Constructor<?> constructor, MethodHandles.Lookup lookup, Class<FInter> functionalInterface){
		if(lookup.lookupClass() != constructor.getDeclaringClass()){
			throw new IllegalArgumentException(lookup.lookupClass().getName() + " != " + constructor.getDeclaringClass().getName());
		}
		try{
			var access = new AccessProvider.DirectLookup(lookup);
			return access.makeLambda(constructor, functionalInterface);
		}catch(Throwable e){
			throw new RuntimeException("failed to create lambda for constructor " + constructor + " with " + functionalInterface, e);
		}
	}
	
	public static VarHandle makeVarHandle(Field field) throws IllegalAccessException{
		return access(AccessProvider::unreflect, field, "failed to create VarHandle\n  Field: {}#red", true);
	}
	public static MethodHandle makeMethodHandle(@NotNull Constructor<?> constructor) throws IllegalAccessException{
		return access(AccessProvider::unreflect, constructor, "failed to create MethodHandle\n  Constructor: {}#red", true);
	}
	public static MethodHandle makeMethodHandle(@NotNull Method method) throws IllegalAccessException{
		return access(AccessProvider::unreflect, method, "failed to create MethodHandle\n  Method: {}#red", true);
	}
	public static Class<?> defineClass(Class<?> target, byte[] bytecode) throws IllegalAccessException{
		return access(AccessProvider::defineClass, target, bytecode, "failed to define class\n  Target: {}#red", true);
	}
	public static AccessProvider findAccess(Class<?> target, Mode... modes) throws IllegalAccessException{
		return access(AccessProvider::adapt, target, modes, "failed to find AccessProvider\n  Target: {}#red", true);
	}
	
	public static <FInter, T extends FInter> T findConstructor(@NotNull Class<?> clazz, Class<FInter> functionalInterface, boolean optimized) throws IllegalAccessException{
		Class<?>[] args = getArgsU(functionalInterface);
		return findConstructorArgs(clazz, functionalInterface, optimized, args);
	}
	
	private static final WeakKeyValueMap<Class<?>, Class<?>[]> ARG_CACHE = new WeakKeyValueMap.Sync<>();
	public static <FInter> Class<?>[] getArgs(Class<FInter> functionalInterface){
		return getArgsU(functionalInterface).clone();
	}
	private static <FInter> Class<?>[] getArgsU(Class<FInter> functionalInterface){
		var ref = ARG_CACHE.get(functionalInterface);
		if(ref != null) return ref;
		
		var args = AccessUtils.getFunctionalMethod(functionalInterface).getParameterTypes();
		ARG_CACHE.put(functionalInterface, args);
		return args;
	}
	
	@NotNull
	public static <FInter, T extends FInter>
	T findConstructorArgs(@NotNull Class<?> clazz, Class<FInter> functionalInterface, boolean optimized, Class<?>... parameterTypes) throws IllegalAccessException{
		try{
			Constructor<?> lconst;
			try{
				lconst = clazz.getDeclaredConstructor(parameterTypes);
			}catch(ReflectiveOperationException e){
				lconst = clazz.getConstructor(parameterTypes);
			}
			return makeLambda(lconst, functionalInterface, optimized);
		}catch(IllegalAccessException e){
			throw e;
		}catch(ReflectiveOperationException ce){
			
			try{
				Method of = clazz.getDeclaredMethod("of", parameterTypes);
				if(!Modifier.isStatic(of.getModifiers())) throw new ReflectiveOperationException(of + " not static");
				if(!of.getReturnType().equals(clazz)) throw new ReflectiveOperationException(of + " does not return " + clazz);
				
				return makeLambda(of, functionalInterface, optimized);
			}catch(ReflectiveOperationException ofe){
				var argStr = switch(parameterTypes.length){
					case 0 -> "empty arguments";
					case 1 -> "argument of " + parameterTypes[0];
					default -> "arguments of " + Arrays.toString(parameterTypes);
				};
				var e = new MissingConstructor("fmt", "{}#red does not have a valid constructor or \"of\" static method with {}#yellow", clazz.getName(), argStr);
				e.addSuppressed(ce);
				e.addSuppressed(ofe);
				throw e;
			}
		}
	}
	
	private interface AccessLambda<T, V>{
		T call(AccessProvider acc, V value) throws IllegalAccessException;
	}
	
	private interface AccessLambda2<T, V1, V2>{
		T call(AccessProvider acc, V1 value1, V2 value2) throws IllegalAccessException;
	}
	
	private static <T, V1, V2> T access(AccessLambda2<T, V1, V2> fn, V1 value1, V2 value2, String errorMessage, boolean optimized) throws IllegalAccessException{
		return access((acc, v1) -> fn.call(acc, v1, value2), value1, errorMessage, optimized);
	}
	private static <T, V> T access(AccessLambda<T, V> fn, V value, String errorMessage, boolean optimized) throws IllegalAccessException{
		if(!optimized){
			try{
				return fn.call(UNOPTIMIZED_ACCESS, value);
			}catch(Throwable ignore){ }
		}
		
		var target = targetClass(value);
		
		// Module-owned / self consent is the fast path.
		if(target != null){
			var direct = tryDirect(target, fn, value);
			if(direct != null) return direct;
		}
		
		try{
			return fn.call(PUBLIC_ACCESS, value);
		}catch(IllegalAccessException ignored){ }
		
		// Discover the anchor without initializing the target: a class whose initializer compiles its own
		// struct (Chunk, Reference) would deadlock against the StagedInit worker doing the compilation.
		if(target != null && ensureModuleConsent(target)){
			var direct = tryDirect(target, fn, value);
			if(direct != null) return direct;
		}
		
		if(target != null) Utils.ensureClassLoaded(target);
		
		// the target may have consented while being initialized above
		if(target != null){
			var direct = tryDirect(target, fn, value);
			if(direct != null) return direct;
		}
		
		throw new IllegalAccessException(
			Log.fmt(errorMessage, value) + "\n" +
			"  The target has no module consent and the request cannot be satisfied by public access. " +
			"Provide full access for the target's module by adding a DFSAccessConsent class (static{ IOInstance.allowFullAccess(MethodHandles.lookup()); }) " +
			"in the target's package, or declare a com.lapissea.dfs.access.AccessConsent provider in module-info.java."
		);
	}
	
	private static <T, V> T tryDirect(Class<?> target, AccessLambda<T, V> fn, V value){
		var lookup = resolveLookup(target);
		if(lookup != null){
			try{
				return fn.call(new AccessProvider.DirectLookup(lookup), value);
			}catch(IllegalAccessException ignored){ }
		}
		return null;
	}
	
}
