package com.lapissea.dfs.type.compilation;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.exceptions.MalformedTemplateStruct;
import com.lapissea.dfs.internal.Access;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.type.GetAnnotation;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.compilation.CompilationTools.FieldStub;
import com.lapissea.dfs.type.compilation.CompilationTools.Style;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.ClosableLock;
import com.lapissea.dfs.utils.PerKeyLock;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match.Some;
import com.lapissea.jorth.BytecodeUtils;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.ref.WeakReference;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static com.lapissea.dfs.type.IOInstance.Def.IMPL_COMPLETION_POSTFIX;
import static com.lapissea.dfs.type.compilation.JorthUtils.writeAnnotations;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NOT_NULL;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static com.lapissea.util.ConsoleColors.*;

public final class DefInstanceCompiler{
	
	
	//////////////////////////////// INIT /////////////////////////////////
	
	static{
		if(!Boolean.getBoolean("jorth.noPreload")){
			Thread.startVirtualThread(() -> {
				try{
					Jorth.generateClass(null, "A", writer -> {
						writer.codePart().close();
						writer.write(
							"""
								class A start
								
									field a #String
								
									function a
										arg a int
										returns int
									start
										get #arg a
									end
								end
								"""
						);
					});
				}catch(MalformedJorth e){
					e.printStackTrace();
				}
			});
		}
	}
	
	
	//////////////////////////////// DATA /////////////////////////////////
	
	
	private record Specials(
		Optional<Method> set,
		Optional<Method> toStr,
		Optional<Method> toShortStr
	){ }
	
	private record FieldInfo(String name, Type type, List<Annotation> annotations, Optional<FieldStub> getter, Optional<FieldStub> setter){
		IterablePP<FieldStub> stubs(){ return Iters.ofPresent(getter, setter); }
		@Override
		public String toString(){
			return "{" +
			       name + ": " + Utils.typeToHuman(type) +
			       getter.map(v -> " getter: " + v).orElse("") +
			       setter.map(v -> " setter: " + v).orElse("") +
			       "}";
		}
	}
	
	record CompletionInfo<T extends IOInstance<T>>(
		Class<T> base, Class<T> completed,
		Set<String> completedGetters
	){
		record Weak<T extends IOInstance<T>>(
			WeakReference<Class<T>> base, WeakReference<Class<T>> completed,
			Set<String> completedGetters
		){
			CompletionInfo<T> deref(){
				var base      = this.base.get();
				var completed = this.completed.get();
				if(base == null || completed == null) return null;
				return new CompletionInfo<>(base, completed, completedGetters);
			}
		}
		Weak<T> weakRef(){
			return new Weak<>(new WeakReference<>(base), new WeakReference<>(completed), completedGetters);
		}
		
		CompletionInfo{
			Objects.requireNonNull(base);
			Objects.requireNonNull(completed);
			Objects.requireNonNull(completedGetters);
		}
		
		private static final Map<Class<?>, CompletionInfo.Weak<?>> COMPLETION_CACHE = Collections.synchronizedMap(new WeakHashMap<>());
		private static final PerKeyLock<Class<?>>                  COMPLETION_LOCK  = new PerKeyLock<>();
		
		@SuppressWarnings("unchecked")
		private static <T extends IOInstance<T>> CompletionInfo<T> completeCached(Class<T> interf){
			var c = getCached(interf);
			if(c != null) return c;
			
			return COMPLETION_LOCK.syncGet(interf, () -> {
				var cachedWeak = (CompletionInfo.Weak<T>)COMPLETION_CACHE.get(interf);
				if(cachedWeak != null){
					var cached = cachedWeak.deref();
					if(cached != null) return cached;
				}
				
				var complete = (CompletionInfo<T>)complete(interf);
				COMPLETION_CACHE.put(interf, complete.weakRef());
				return complete;
			});
		}
		
		@SuppressWarnings("unchecked")
		private static <T extends IOInstance<T>> CompletionInfo<T> getCached(Class<T> interf){
			var cached = (CompletionInfo.Weak<T>)COMPLETION_CACHE.get(interf);
			if(cached != null) return cached.deref();
			return null;
		}
		
		private static <T extends IOInstance<T>> CompletionInfo<?> complete(Class<T> interf){
			
			var getters = new ArrayList<FieldStub>();
			var setters = new ArrayList<FieldStub>();
			collectMethods(interf, getters, setters);
			var style = checkStyles(getters, setters);
			
			var getterNames    = Iters.from(getters).map(FieldStub::varName);
			var setterNames    = Iters.from(setters).map(FieldStub::varName);
			var missingGetters = setterNames.filter(getterNames::noneEquals).toSet();
			
			if(missingGetters.isEmpty()){
				return new CompletionInfo<>(interf, interf, Set.of());
			}
			
			ConfigDefs.CompLogLevel.SMALL.log(
				"Generating completion of {}#cyan - {}",
				() -> List.of(interf.getSimpleName(), missingGetters.isEmpty()? "" : "missing getters: " + missingGetters)
			);
			
			var setterMap = Iters.from(setters).toModMap(FieldStub::varName, Function.identity());
			
			var completionName = interf.getName() + IMPL_COMPLETION_POSTFIX;
			
			var log   = JorthLogger.make();
			var jorth = new Jorth(interf.getClassLoader(), log == null? null : log::log);
			try{
				
				try(var writer = jorth.writer()){
					writeAnnotations(writer, Arrays.asList(interf.getAnnotations()));
					writer.addImportAs(completionName, "typ.impl");
					
					var parms = interf.getTypeParameters();
					for(var parm : parms){
						var bounds = parm.getBounds();
						if(bounds.length != 1){
							throw new NotImplementedException("Implement multi bound type variable");
						}
						writer.write("type-arg {!} {}", parm.getName(), bounds[0]);
					}
					
					String parmsStr;
					if(parms.length == 0) parmsStr = "";
					else parmsStr = Iters.from(parms).joinAsStr(" ", "<", ">", TypeVariable::getName);
					
					writer.write("implements {!}{}", interf.getName(), parmsStr);
					writer.write("interface #typ.impl start");
					
					unmappedClassFn(writer, interf);
					
					for(String name : missingGetters){
						writer.write(
							"""
								function {!0}
									{1} returns
								end
								""",
							style.mapGetter(name),
							setterMap.get(name).type()
						);
					}
					writer.wEnd();
				}
				
				var file = jorth.getClassFile(completionName);
				
				ClassGenerationCommons.dumpClassName(completionName, file);
				if(log != null){
					Log.log("Generated jorth:\n" + log.output());
					BytecodeUtils.printClass(file);
				}
				
				//noinspection unchecked
				var completed = (Class<T>)Access.defineClass(interf, file);
				return new CompletionInfo<>(interf, completed, Set.copyOf(missingGetters));
			}catch(MalformedJorth e){
				throw new RuntimeException(e);
			}
		}
		
		@SuppressWarnings("unchecked")
		private static <T extends IOInstance.Def<T>> Optional<Class<T>> findSourceInterface(Class<?> impl){
			try{
				var getCls = impl.getDeclaredMethod(GET_UNMAPPED_CLASS_FN);
				var cls    = (Class<T>)getCls.invoke(null);
				return Optional.of(cls);
			}catch(NoSuchMethodException e){
				throw new ShouldNeverHappenError("No " + GET_UNMAPPED_CLASS_FN + " on class " + impl.getTypeName());
			}catch(InvocationTargetException|IllegalAccessException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	public record Key<T extends IOInstance<T>>(Class<T> clazz, Optional<Set<String>> includeNames){
		public Key(Class<T> clazz){
			this(clazz, Optional.empty());
		}
		public Key(Class<T> clazz, Optional<Set<String>> includeNames){
			this.clazz = Objects.requireNonNull(clazz);
			this.includeNames = includeNames.map(Set::copyOf);
		}
		
		private CompletionInfo<T> complete(){
			return CompletionInfo.completeCached(clazz);
		}
	}
	
	private record Result<T extends IOInstance<T>>(Class<T> impl, Optional<List<FieldInfo>> oOrderedFields,
	                                               Optional<List<FieldInfo>> oPartialFields){ }
	
	private static final class ImplNode<T extends IOInstance<T>>{
		enum State{
			NEW, COMPILING, DONE
		}
		
		private final ClosableLock lock = ClosableLock.reentrant();
		
		private       State        state = State.NEW;
		private final Key<T>       key;
		private       Class<T>     impl;
		private       MethodHandle dataConstructor;
		private       MethodHandle partialDataConstructor;
		
		public ImplNode(Key<T> key){
			this.key = key;
		}
		
		private void init(Result<T> result){
			this.impl = result.impl;
			dataConstructor = result.oOrderedFields.map(this::getCtor).orElse(null);
			partialDataConstructor = result.oPartialFields.map(this::getCtor).orElse(null);
		}
		private MethodHandle getCtor(List<FieldInfo> fields){
			try{
				var ctr = impl.getConstructor(Iters.from(fields).map(f -> Utils.typeToRaw(f.type)).toArray(Class[]::new));
				return Access.makeMethodHandle(ctr);
			}catch(NoSuchMethodException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	
	private static final List<Class<?>> IGNORE_TYPES =
		Iters.concatN1(
			Iters.<Class<?>>iterate(
				IOInstance.Def.class,
				Objects::nonNull,
				cl -> Iters.from(cl.getInterfaces())
				           .findFirst()
				           .orElse(null)),
			Object.class
		).toList();
	
	
	private static final ConcurrentHashMap<Key<?>, ImplNode<?>> CACHE = new ConcurrentHashMap<>();
	
	private static final String GET_UNMAPPED_CLASS_FN = "$$fetchUnmappedClass";
	
	//////////////////////////////// API /////////////////////////////////
	
	
	public static <T extends IOInstance.Def<T>> Optional<Class<T>> unmap(Class<?> impl){
		Objects.requireNonNull(impl);
		if(!UtilL.instanceOf(impl, IOInstance.Def.class)){
			throw new IllegalArgumentException(impl.getName() + "");
		}
		return CompletionInfo.findSourceInterface(impl);
	}
	
	public static <T extends IOInstance<T>> MethodHandle dataConstructor(Class<T> interf){
		var key = new Key<>(interf);
		return dataConstructor(key, true);
	}
	public static <T extends IOInstance<T>> MethodHandle dataConstructor(Key<T> key, boolean fullCtor){
		@SuppressWarnings("unchecked")
		var node = (ImplNode<T>)CACHE.get(key);
		if(node == null || node.state != ImplNode.State.DONE){
			node = getNode(key);
		}
		MethodHandle ctor;
		if(fullCtor){
			ctor = node.dataConstructor;
		}else{
			if(key.includeNames.isEmpty()){
				throw new IllegalArgumentException("Partial constructor can only exist on a partial implementation");
			}
			ctor = node.partialDataConstructor;
		}
		if(ctor == null){
			throw new RuntimeException(Log.fmt("Please add {}#yellow to {}#red", IOInstance.Order.class.getName(), key.clazz.getName()));
		}
		return ctor;
	}
	
	public static <T extends IOInstance<T>> Class<T> getImpl(Class<T> interf){
		return getNode(new Key<>(interf)).impl;
	}
	
	public static <T extends IOInstance.Def<T>> Class<T> getImplPartial(Key<T> key){
		if(key.includeNames.isEmpty()) throw new IllegalArgumentException("Names can not be empty");
		return getNode(key).impl;
	}
	
	
	//////////////////////////////// IMPLEMENTATION /////////////////////////////////
	
	
	private static <T extends IOInstance<T>> ImplNode<T> getNode(Key<T> key){
		Class<T> interf = key.clazz;
		if(!IOInstance.Def.isDefinition(interf)){
			throw new IllegalArgumentException(interf + " type must be an IOInstance.Def");
		}
		
		@SuppressWarnings("unchecked")
		var node = (ImplNode<T>)CACHE.computeIfAbsent(key, i -> new ImplNode<>((Key<T>)i));
		
		try(var ignored = node.lock.open()){
			return switch(node.state){
				case null -> throw new ShouldNeverHappenError();
				case NEW -> {
					compileNode(node);
					yield node;
				}
				case COMPILING -> throw new MalformedStruct("Type requires itself to compile");
				case DONE -> node;
			};
		}catch(Throwable e){
			node.state = ImplNode.State.NEW;
			throw e;
		}
	}
	
	private static <T extends IOInstance<T>> void compileNode(ImplNode<T> node){
		node.state = ImplNode.State.COMPILING;
		
		Key<T> key = node.key;
		
		var inter = key.clazz;
		
		ConfigDefs.CompLogLevel.SMALL.log(
			"Generating implementation of: {}#cyan{}#cyanBright {}classloader: {}", () -> {
				var cols = List.of(BLACK, RED, GREEN, YELLOW, BLUE, PURPLE, CYAN, WHITE);
				var hash = inter.getClassLoader().hashCode();
				return List.of(
					Utils.classPathHeadless(inter), inter.getSimpleName(),
					node.key.includeNames.map(Object::toString).map(s -> "fields: " + s + " - ").orElse(""),
					cols.get((int)(Integer.toUnsignedLong(hash)%cols.size())) + Integer.toHexString(hash) + RESET
				);
			}
		);
		
		var humanName = inter.getSimpleName();
		var completed = key.complete();
		var compiled  = compile(completed, key.includeNames, humanName);
		
		node.init(compiled);
		
		node.state = ImplNode.State.DONE;
	}
	
	private static <T extends IOInstance<T>> Result<T> compile(CompletionInfo<T> completion, Optional<Set<String>> includeNames, String humanName){
		Class<T> completeInter = completion.completed;
		
		var getters  = new ArrayList<FieldStub>();
		var setters  = new ArrayList<FieldStub>();
		var specials = collectMethods(completeInter, getters, setters);
		
		checkStyles(getters, setters);
		
		var fieldInfo = mergeStubs(getters, setters);
		
		includeNames.ifPresent(strings -> checkIncludeNames(fieldInfo, strings));
		
		var orderedFields = getOrder(completeInter, fieldInfo)
			                    .map(names -> Iters.from(names)
			                                       .map(name -> Iters.from(fieldInfo).firstMatching(f -> f.name.equals(name)))
			                                       .toList(Optional::orElseThrow))
			                    .or(() -> fieldInfo.size()>1? Optional.empty() : Optional.of(fieldInfo));
		
		checkClass(completeInter, specials, orderedFields);
		
		checkTypes(fieldInfo);
		checkAnnotations(fieldInfo);
		checkModel(fieldInfo);
		
		try{
			for(int i = 0; ; i++){
				try{
					var cls = generateImpl(completion, includeNames, specials, fieldInfo, orderedFields, humanName, i);
					
					var ordered = orderedFields.flatMap(oFields -> includeNames.map(inc -> Iters.from(oFields).filter(f -> inc.contains(f.name)).toList()));
					return new Result<>(cls, orderedFields, ordered);
				}catch(LinkageError e){
					if(!e.getMessage().contains("duplicate class definition")){
						throw new RuntimeException(e);
					}
				}
			}
		}catch(Throwable e){
			if(ConfigDefs.CLASSGEN_EXIT_ON_FAIL.resolveVal()){
				new RuntimeException("failed to compile implementation for " + completeInter.getName(), e).printStackTrace();
				System.exit(1);
			}
			throw e;
		}
	}
	
	private static void checkIncludeNames(List<FieldInfo> fieldInfo, Set<String> includeNames){
		for(String includeName : includeNames){
			var infoNames = Iters.from(fieldInfo).map(FieldInfo::name);
			if(infoNames.noneMatch(includeName::equals)){
				throw new IllegalArgumentException(includeName + " is not a valid field name. \n" +
				                                   "Field names: " + infoNames.joinAsStr(", "));
			}
		}
	}
	
	private static <T extends IOInstance<T>> void checkClass(Class<T> interf, Specials specials, Optional<List<FieldInfo>> oOrderedFields){
		
		if(specials.set.isPresent()){
			var set = specials.set.get();
			
			if(oOrderedFields.isEmpty()){
				throw new MalformedTemplateStruct(interf.getName() + " has a full setter but no argument order. Please add " + IOInstance.Order.class.getName() + " to the type");
			}
			
			var orderedFields = oOrderedFields.get();
			
			if(set.getParameterCount() != orderedFields.size()){
				throw new MalformedTemplateStruct(set + " has " + set.getParameterCount() + " parameters but has " + orderedFields.size() + " fields");
			}
			
			var parms = set.getGenericParameterTypes();
			for(int i = 0; i<orderedFields.size(); i++){
				var field    = orderedFields.get(i);
				var parmType = parms[i];
				
				if(field.type.equals(parmType)) continue;
				
				//TODO: implement fits type instead of exact match
				throw new MalformedTemplateStruct(field.name + " has the type of " + field.type.getTypeName() + " but set argument is " + parmType);
			}
		}
	}
	
	private static <T extends IOInstance<T>> Class<T> generateImpl(
		CompletionInfo<T> completion, Optional<Set<String>> includeNames, Specials specials,
		List<FieldInfo> fieldInfo, Optional<List<FieldInfo>> orderedFields,
		String humanName, int numAddon
	){
		var interf = completion.completed;
		
		var implName = interf.getName() +
		               IOInstance.Def.IMPL_NAME_POSTFIX + (numAddon != 0? "~" + numAddon : "") +
		               includeNames.map(n -> Iters.from(n).joinAsStr("_", "€€fields~", "")).orElse("");
		
		var log = JorthLogger.make();
		try{
			var jorth = new Jorth(interf.getClassLoader(), log == null? null : log::log);
			
			jorth.addImportAs(implName, "typ.impl");
			jorth.addImportAs(interf.getName(), "typ.interf");
			jorth.addImports(
				Struct.class, Objects.class, IOValue.class,
				ChunkPointer.class, IOInstance.Managed.class,
				UnsupportedOperationException.class
			);
			
			try(var writer = jorth.writer()){
				
				
				var parms    = interf.getTypeParameters();
				var parmsStr = "";
				if(parms.length>0) parmsStr = Iters.from(parms).joinAsStr(" ", "<", ">", TypeVariable::getName);
				
				for(var parm : parms){
					var bounds = parm.getBounds();
					if(bounds.length != 1){
						throw new NotImplementedException("Implement multi bound type variable");
					}
					writer.write("type-arg {!} {}", parm.getName(), bounds[0]);
				}
				
				writer.write("implements #typ.interf" + parmsStr);
				
				writer.write(
					"""
						extends #IOInstance.Managed <#typ.impl{}>
						public class #typ.impl start
						""", parmsStr);
				
				defineStatics(writer, completion.base);
				
				for(var info : fieldInfo){
					if(isFieldIncluded(includeNames, info.name)){
						defineField(writer, info);
						implementUserAccess(writer, info, humanName);
					}else{
						defineNoField(writer, info);
					}
				}
				
				
				var includedFields  = includeNames.map(include -> Iters.from(fieldInfo).filter(f -> include.contains(f.name)).toList()).orElse(fieldInfo);
				var includedOrdered = includeNames.map(include -> orderedFields.map(o -> Iters.from(o).filter(f -> include.contains(f.name)).toList())).orElse(orderedFields);
				
				if(Iters.from(fieldInfo).allMatch(
					f -> f.setter.isPresent() ||
					     Iters.from(f.annotations).anyMatch(a -> a instanceof IONullability n && n.value() == NULLABLE) ||
					     List.of(ChunkPointer.class, Optional.class).contains(Utils.typeToRaw(f.type))
				)){
					generateDefaultConstructor(writer, includedFields);
				}else{
					int a000 = 0;
				}
				generateDataConstructor(writer, orderedFields, includeNames, humanName);//All fields constructor
				if(includeNames.isPresent()){
					generateDataConstructor(writer, includedOrdered, includeNames, humanName);//Included only fields constructor
				}
				
				readOnlyConstructor:
				if(specials.set.isEmpty() && completion.completed != completion.base){
					var setters = new ArrayList<FieldStub>();
					collectMethods(completion.base, new ArrayList<>(), setters);
					var setterNames = Iters.from(setters).toModSet(FieldStub::varName);
					
					var dataFields = Iters.from(includedFields).filter(
						f -> !setterNames.contains(f.name) && (includeNames.isEmpty() || includeNames.get().contains(f.name))
					).toList();
					if(dataFields.isEmpty()) break readOnlyConstructor;
					if(orderedFields.isEmpty() && dataFields.size()>1) break readOnlyConstructor;
					
					var dfSet = Set.copyOf(dataFields);
					
					for(Optional<List<FieldInfo>> op : List.of(orderedFields, includedOrdered)){
						if(op.isEmpty()) continue;
						var fieldSet = Set.copyOf(op.get());
						if(dfSet.equals(fieldSet)){
							break readOnlyConstructor;
						}
					}
					
					generateDataConstructor(writer, Optional.of(dataFields), Optional.empty(), humanName);
				}
				
				if(specials.set.isPresent()){
					var set = specials.set.get();
					
					for(FieldInfo info : orderedFields.orElseThrow()){
						writer.write(
							"arg {!} {}",
							info.name,
							info.type);
					}
					
					writer.write(
						"""
							public function {} start
							""",
						set.getName());
					
					for(FieldInfo info : includedOrdered.orElseThrow()){
						writer.write("get #arg {!}", info.name);
						if(info.type == ChunkPointer.class || Utils.typeToRaw(info.type) == Optional.class){
							JorthUtils.nullCheckDup(writer, fieldNullMsg(info, humanName));
						}
						writer.write("set this {!}", info.name);
					}
					writer.wEnd();
				}
				
				generateSpecialToString(interf, writer, specials);
				
				writer.wEnd();
			}
			
			var file = jorth.getClassFile(implName);
			ClassGenerationCommons.dumpClassName(implName, file);
			if(log != null){
				Log.log(log.output());
				BytecodeUtils.printClass(file);
			}
			
			//noinspection unchecked
			return (Class<T>)Access.defineClass(interf, file);
		}catch(MalformedJorth e){
			throw new RuntimeException("Failed to generate implementation for: " + interf.getName(), e);
		}
	}
	
	private static void unmappedClassFn(CodeStream writer, Class<?> baseClazz) throws MalformedJorth{
		writer.write(
			"""
				public static function {}
					returns #Class
				start
					class {}
				end
				""",
			GET_UNMAPPED_CLASS_FN, baseClazz
		);
	}
	
	private static Boolean isFieldIncluded(Optional<Set<String>> includeNames, String name){
		return includeNames.map(ns -> ns.contains(name)).orElse(true);
	}
	
	private static <T extends IOInstance<T>> void generateSpecialToString(Class<T> interf, CodeStream writer, Specials specials) throws MalformedJorth{
		if(specials.toStr.isPresent()){
			generateSpecialToString(interf, writer, specials.toStr.get());
		}
		if(specials.toShortStr.isPresent()){
			generateSpecialToString(interf, writer, specials.toShortStr.get());
		}
	}
	
	private static <T extends IOInstance<T>> void generateSpecialToString(Class<T> interf, CodeStream writer, Method method) throws MalformedJorth{
		writer.write(
			"""
				public function {!0}
					returns #String
				start
					static call {!1} {!0} start
						get this this
					end
				end
				""",
			method.getName(),
			interf.getName());
	}
	
	private static void generateDefaultConstructor(CodeStream writer, List<FieldInfo> fieldInfo) throws MalformedJorth{
		writer.write(
			"""
				public function <init>
				start
					super start
						static call #typ.impl $STRUCT
					end
				""");
		
		for(FieldInfo info : fieldInfo){
			if(info.type == ChunkPointer.class){
				writer.write("get #ChunkPointer NULL");
				writer.write("set this {!}", info.name);
			}else if(Utils.typeToRaw(info.type) == Optional.class){
				writer.write("static call {} empty", Optional.class);
				writer.write("set this {!}", info.name);
			}
		}
		writer.wEnd();
	}
	
	private static void generateDataConstructor(CodeStream writer, Optional<List<FieldInfo>> oOrderedFields, Optional<Set<String>> includeNames, String baseClassSimpleName) throws MalformedJorth{
		if(oOrderedFields.filter(d -> !d.isEmpty()).isEmpty()){
			return;
		}
		var orderedFields = oOrderedFields.get();
		
		writer.write(
			"""
				public function <init>
					template-for #e in {0} start
						arg #e.key #e.value
					end
				start
					super start
						static call #typ.impl $STRUCT
					end
				""",
			Iters.rangeMap(0, orderedFields.size(), i -> new SimpleEntry<>("arg" + i, orderedFields.get(i).type))
		);
		
		for(int i = 0; i<orderedFields.size(); i++){
			FieldInfo info     = orderedFields.get(i);
			boolean   included = includeNames.map(in -> in.contains(info.name)).orElse(true);
			
			boolean nullCheck;
			if(info.type instanceof Class<?> c && c.isPrimitive()) nullCheck = false;
			else nullCheck = info.type == ChunkPointer.class || info.type == Optional.class ||
			                 Iters.from(info.annotations)
			                      .firstMatching(a -> a instanceof IONullability)
			                      .map(a -> ((IONullability)a).value())
			                      .orElse(NOT_NULL) == NOT_NULL;
			
			if(!included){
				if(nullCheck){
					JorthUtils.nullCheck(writer, "get #arg arg" + i, fieldNullMsg(info, baseClassSimpleName));
				}
				continue;
			}
			
			writer.write("get #arg arg" + i);
			if(nullCheck){
				JorthUtils.nullCheckDup(writer, fieldNullMsg(info, baseClassSimpleName));
			}
			writer.write("set this {!}", info.name);
		}
		writer.wEnd();
	}
	
	private static Set<String> collectNames(Class<?> its){
		var getters = new ArrayList<FieldStub>();
		var setters = new ArrayList<FieldStub>();
		//noinspection unchecked,rawtypes
		collectMethods((Class<IOInstance>)its, getters, setters);
		return Iters.concat(getters, setters).toModSet(FieldStub::varName);
	}
	
	private static Optional<Class<?>> upperSame(Class<?> interf){
		var its = Iters.from(interf.getInterfaces()).filter(IOInstance.Def::isDefinition).toModList();
		if(its.size() != 1) return Optional.empty();
		
		Set<String> parentNames = collectNames(its.getFirst());
		Set<String> thisNames   = collectNames(interf);
		if(parentNames.equals(thisNames)){
			return Optional.of(its.getFirst());
		}
		return Optional.empty();
	}
	private static Optional<List<String>> getOrder(Class<?> interf, List<FieldInfo> fieldInfo){
		var order = interf.getAnnotation(IOInstance.Order.class);
		if(order == null){
			var its = Iters.from(interf.getInterfaces()).filter(IOInstance.Def::isDefinition).toModList();
			if(its.size() != 1) return Optional.empty();
			
			var upper = upperSame(interf);
			return upper.flatMap(u -> getOrder(u, fieldInfo));
		}
		
		var check   = Iters.from(fieldInfo).toModSet(FieldInfo::name);
		var ordered = List.of(order.value());
		
		for(String name : ordered){
			if(check.remove(name)) continue;
			throw new MalformedTemplateStruct(
				name + " does not exist in " + interf.getName() + ".\n" +
				"Existing field names: " + Iters.from(fieldInfo).toModList(FieldInfo::name)
			);
		}
		
		if(!check.isEmpty()){
			throw new MalformedTemplateStruct(check + " are not listed in the order annotation");
		}
		
		return Optional.of(ordered);
	}
	
	private static void defineStatics(CodeStream writer, Class<?> baseClazz) throws MalformedJorth{
		writer.write(
			"""
				private static field $V_STRUCT #Struct
				
				private static function $STRUCT
					returns #Struct
				start
					static call {} isNull start
						get #typ.impl $V_STRUCT
					end
					if start
						static call #Struct of start
							class #typ.impl
						end
						set #typ.impl $V_STRUCT
					end
					get #typ.impl $V_STRUCT
				end
				
				function <clinit> start
					static call {} allowFullAccess start
						static call {} lookup
					end
				end
				""",
			Objects.class, IOInstance.Managed.class, MethodHandles.class);
		
		unmappedClassFn(writer, baseClazz);
	}
	
	private static void implementUserAccess(CodeStream writer, FieldInfo info, String classHumanName) throws MalformedJorth{
		
		var getterName = info.getter.map(s -> s.method().getName()).orElseGet(() -> {
			var setter = info.setter.orElseThrow();
			return switch(setter.style()){
				case NAMED -> "get" + TextUtil.firstToUpperCase(setter.varName());
				case RAW -> setter.varName();
			};
		});
		var setterName = info.setter.map(s -> s.method().getName());
		writer.write(
			"""
				@ #IOValue start name '{!2}' end
				@ #Override
				public function {!0}
					returns {1}
				start
					get this {!2}
				end
				""",
			getterName,
			info.type,
			info.name
		);
		
		if(setterName.isPresent()){
			writer.write(
				"""
					@ #IOValue start name '{!2}' end
					@ #Override
					public function {!0}
						arg arg1 {1}
					start
						get #arg arg1
					""",
				setterName.get(),
				info.type,
				info.name
			);
			
			if(info.type == ChunkPointer.class || Utils.typeToRaw(info.type) == Optional.class){
				JorthUtils.nullCheckDup(writer, fieldNullMsg(info, classHumanName));
			}
			
			writer.write(
				"""
						set this {!}
					end
					""",
				info.name
			);
		}
	}
	
	private static String fieldNullMsg(FieldInfo info, String classHumanName){
		return '"' + classHumanName + "." + info.name + "\" can not be null!";
	}
	
	private static void defineNoField(CodeStream writer, FieldInfo info) throws MalformedJorth{
		var getterName = info.getter.map(v -> v.method().getName()).orElseGet(() -> "get" + TextUtil.firstToUpperCase(info.name));
		var setterName = info.setter.map(v -> v.method().getName()).orElseGet(() -> "set" + TextUtil.firstToUpperCase(info.name));
		
		var anns = new ArrayList<>(info.annotations);
		anns.removeIf(a -> (a instanceof IOValue v && v.name().isEmpty()) || a instanceof Override);
		
		var valAnn = Iters.from(anns).firstMatching(a -> a instanceof IOValue).orElseGet(() -> Annotations.make(IOValue.class, Map.of("name", info.name)));
		
		if(Iters.from(anns).noneMatch(a -> a instanceof IOValue)) anns.add(valAnn);
		
		writeAnnotations(writer, anns);
		writer.write(
			"""
				public function {!}
					returns {}
				start
					new #UnsupportedOperationException
					throw
				end
				""",
			getterName,
			info.type
		);
		
		writeAnnotations(writer, List.of(valAnn));
		writer.write(
			"""
				public function {!}
					arg arg0 {}
				start
					new #UnsupportedOperationException
					throw
				end
				""",
			setterName,
			info.type
		);
	}
	
	private static void defineField(CodeStream writer, FieldInfo info) throws MalformedJorth{
		writeAnnotations(writer, info.annotations);
		writer.write(
			"private {} field {!} {}",
			info.setter.isEmpty()? "final" : "",
			info.name,
			info.type
		);
	}
	
	private static <T extends IOInstance<T>> Specials collectMethods(Class<T> interf, Collection<FieldStub> getters, Collection<FieldStub> setters){
		var set        = Optional.<Method>empty();
		var toStr      = Optional.<Method>empty();
		var toShortStr = Optional.<Method>empty();
		
		var q = new ArrayDeque<Class<?>>(2);
		q.addLast(interf);
		var last = new ArrayList<Class<?>>(2);
		while(!q.isEmpty()){
			var clazz = q.removeFirst();
			last.add(clazz);
			
			toStr = toStr.or(() -> {
				for(Method m : clazz.getDeclaredMethods()){
					if(isToString(clazz, m, "toString")){
						return Optional.of(m);
					}
				}
				return Optional.empty();
			});
			toShortStr = toShortStr.or(() -> Iters.from(clazz.getMethods()).firstMatching(m -> isToString(clazz, m, "toShortString")));
			if(toStr.isPresent() && toShortStr.isPresent()) break;
			
			if(q.isEmpty()){
				Iters.from(last)
				     .flatMapArray(Class::getInterfaces)
				     .filter(i -> i != IOInstance.Def.class && UtilL.instanceOf(interf, IOInstance.Def.class))
				     .forEach(q::addLast);
				last.clear();
			}
		}
		
		for(Method method : interf.getMethods()){
			if(Modifier.isStatic(method.getModifiers()) || !Modifier.isAbstract(method.getModifiers())){
				continue;
			}
			if(IGNORE_TYPES.contains(method.getDeclaringClass())){
				continue;
			}
			
			if(List.of("set", "setAll").contains(method.getName())){
				if(method.getReturnType() != void.class) throw new MalformedTemplateStruct("set can not have a return type");
				if(set.isPresent()) throw new MalformedTemplateStruct("duplicate set method");
				set = Optional.of(method);
				continue;
			}
			if(CompilationTools.asStub(method) instanceof Some(var stub)){
				(stub.isGetter()? getters : setters).add(stub);
			}else{
				throw new MalformedTemplateStruct(method + " is not a setter or a getter!");
			}
		}
		
		return new Specials(set, toStr, toShortStr);
	}
	
	private static boolean isToString(Class<?> interf, Method method, String name){
		if(!Modifier.isStatic(method.getModifiers())) return false;
		if(!method.getName().equals(name)) return false;
		if(method.getReturnType() != String.class) return false;
		if(method.getParameterCount() != 1) return false;
		var type = method.getParameterTypes()[0];
		return UtilL.instanceOf(type, interf);
	}
	
	private static List<FieldInfo> mergeStubs(List<FieldStub> getters, List<FieldStub> setters){
		return Iters.concat(getters, setters)
		            .map(FieldStub::varName)
		            .distinct()
		            .map(name -> {
			            var getter = Iters.from(getters).firstMatching(s -> s.varName().equals(name));
			            var setter = Iters.from(setters).firstMatching(s -> s.varName().equals(name));
			            
			            var gors = getter.or(() -> setter).orElseThrow();
			            
			            var type = gors.type();
			            
			            var anns = Iters.ofPresent(getter, setter)
			                            .flatMapArray(f -> f.method().getAnnotations())
			                            .filter(a -> FieldCompiler.ANNOTATION_TYPES.contains(a.annotationType()))
			                            .toModList();
			            
			            IOValue valBack = null;
			            var     iter    = anns.iterator();
			            while(iter.hasNext()){
				            var ann = iter.next();
				            if(ann instanceof IOValue val){
					            if(val.name().isEmpty()){
						            valBack = val;
						            iter.remove();
						            continue;
					            }
					            throw new MalformedTemplateStruct(gors.varName() + ": @IOValue can not contain a name");
				            }
			            }
			            if(valBack == null) valBack = Annotations.make(IOValue.class);
			            anns.add(valBack);
			            
			            return new FieldInfo(name, type, anns, getter, setter);
		            })
		            .toModList();
	}
	
	private static Style checkStyles(List<FieldStub> getters, List<FieldStub> setters){
		Map<Style, List<FieldStub>> styles = Iters.concat(getters, setters).toGrouping(FieldStub::style);
		if(styles.isEmpty()){
			return Style.NAMED;
		}
		if(styles.size()>1){
			var style = Iters.entries(styles).reduce((a, b) -> a.getValue().size()>b.getValue().size()? a : b).map(Map.Entry::getKey).orElseThrow();
			throw new MalformedTemplateStruct(
				"Inconsistent getter/setter styles!\n" +
				"Style patterns:\n" + Iters.keys(styles).joinAsStr("\n", s -> "\t" + s + ":\t" + s.humanPattern) + "\n" +
				"Most common style is:\n" + style + "\n" +
				"Bad styles:\n" +
				Iters.entries(styles)
				     .filter(e -> e.getKey() != style)
				     .flatMap(Map.Entry::getValue)
				     .joinAsStr("\n", s -> "\t" + s.method().getName() + ":\t" + s.style())
			);
		}
		return Iters.keys(styles).getFirst();
	}
	
	private static void checkAnnotations(List<FieldInfo> fields){
		Iters.from(fields).flatOptionals(
			gs -> {
				var typeGroups = gs.stubs()
				                   .flatMapArray(g -> g.method().getAnnotations())
				                   .filter(a -> FieldCompiler.ANNOTATION_TYPES.contains(a.annotationType()))
				                   .toGrouping(Annotation::annotationType);
				return Iters.values(typeGroups)
				            .filter(l -> l.size()>1)
				            .joinAsOptionalStr("\n", l -> "\t\t" + l.getFirst().annotationType().getName())
				            .map(names -> "\t" + gs.name + ":\n" + names);
			}
		).joinAsOptionalStr("\n").ifPresent(problems -> {
			throw new MalformedTemplateStruct("Duplicate annotations:\n" + problems);
		});
	}
	
	@SuppressWarnings("unchecked")
	private static boolean typeEquals(Type aT, Type bT){
		if(aT == null || bT == null) return aT == bT;
		return switch(aT){
			case Class<?> a -> a.equals(bT);
			case ParameterizedType a -> {
				yield bT instanceof ParameterizedType b &&
				      typeEquals(a.getRawType(), b.getRawType()) &&
				      typeEquals(a.getOwnerType(), b.getOwnerType()) &&
				      typesEqual(a.getActualTypeArguments(), b.getActualTypeArguments());
			}
			case TypeVariable<?> a -> {
				if(!(bT instanceof TypeVariable<?> b)) yield false;
				GenericDeclaration aDec = a.getGenericDeclaration(), bDec = b.getGenericDeclaration();
				//noinspection rawtypes
				if(aDec instanceof Class aTyp && bDec instanceof Class bTyp){
					var aComp = CompletionInfo.getCached(aTyp);
					if(aComp != null) aDec = aComp.completed;
					var bComp = CompletionInfo.getCached(bTyp);
					if(bComp != null) bDec = bComp.completed;
				}
				
				yield Objects.equals(aDec, bDec) &&
				      Objects.equals(a.getName(), b.getName());
			}
			case WildcardType a -> {
				yield bT instanceof WildcardType b &&
				      typesEqual(a.getLowerBounds(), b.getLowerBounds()) &&
				      typesEqual(a.getUpperBounds(), b.getUpperBounds());
			}
			case GenericArrayType a -> {
				yield bT instanceof GenericArrayType b &&
				      typeEquals(a.getGenericComponentType(), b.getGenericComponentType());
			}
			default -> aT.equals(bT);
		};
	}
	private static boolean typesEqual(Type[] a, Type[] b){
		if(a == null || b == null) return a == b;
		if(a.length != b.length) return true;
		for(int i = 0; i<a.length; i++){
			if(!typeEquals(a[i], b[i])){
				return false;
			}
		}
		return true;
	}
	private static void checkTypes(List<FieldInfo> fields){
		var result = new StringJoiner("\n");
		
		for(var field : fields){
			if(field.getter.isEmpty() || field.setter.isEmpty()) continue;
			
			FieldStub getter     = field.getter.get(), setter = field.setter.get();
			Type      getterType = getter.type(), setterType = setter.type();
			if(typeEquals(getterType, setterType)){
				continue;
			}
			
			result.add(
				"\t" + field.name + ":\n" +
				"\t\t" + (getter.style() != Style.RAW? getter.method().getName() + ":\t" : "getter: ") + getter.type() + "\n" +
				"\t\t" + (setter.style() != Style.RAW? setter.method().getName() + ":\t" : "setter: ") + setter.type()
			);
		}
		
		if(result.length()>0){
			throw new MalformedTemplateStruct("Mismatched types:\n" + result);
		}
	}
	
	private static void checkModel(List<FieldInfo> fieldInfo){
		for(var field : fieldInfo){
			var ann = GetAnnotation.from(field.annotations);
			FieldRegistry.requireCanCreate(FieldCompiler.getType(field.type, ann), ann);
		}
	}
}
