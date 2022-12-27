package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStruct;
import com.lapissea.cfs.exceptions.MalformedTemplateStruct;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.compilation.CompilationTools.FieldStub;
import com.lapissea.cfs.type.compilation.CompilationTools.Style;
import com.lapissea.cfs.type.compilation.ToStringFormat.ToStringFragment.*;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.utils.ClosableLock;
import com.lapissea.cfs.utils.ReadWriteClosableLock;
import com.lapissea.jorth.BytecodeUtils;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.MalformedJorth;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.IOInstance.Def.IMPL_COMPLETION_POSTFIX;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NOT_NULL;
import static com.lapissea.util.ConsoleColors.*;
import static java.lang.reflect.Modifier.isStatic;

public class DefInstanceCompiler{
	
	public static void init(){ }
	
	static{
		if(!Boolean.getBoolean("jorth.noPreload")){
			Thread.ofVirtual().start(() -> {
				var jorth = new Jorth(null, null);
				try(var writer = jorth.writer()){
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
				}catch(MalformedJorth e){
					e.printStackTrace();
				}
			});
		}
	}
	
	private static final boolean EXIT_ON_FAIL = GlobalConfig.configFlag("classGen.exitOnFail", false);
	
	private record Specials(
		Optional<Method> set,
		Optional<Method> toStr,
		Optional<Method> toShortStr
	){ }
	
	private record FieldInfo(String name, Type type, List<Annotation> annotations, Optional<FieldStub> getter, Optional<FieldStub> setter){
		Stream<FieldStub> stubs(){ return Stream.concat(getter.stream(), setter.stream()); }
		@Override
		public String toString(){
			return "{" +
			       name + ": " + Utils.typeToHuman(type, false) +
			       getter.map(v -> " getter: " + v).orElse("") +
			       setter.map(v -> " setter: " + v).orElse("") +
			       "}";
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
		
		private Key<T> complete(){
			return new Key<>(completeCached(clazz), includeNames);
		}
	}
	
	private record Result<T extends IOInstance<T>>(Class<T> impl, Optional<List<FieldInfo>> oOrderedFields){ }
	
	private static final class ImplNode<T extends IOInstance<T>>{
		enum State{
			NEW, COMPILING, DONE
		}
		
		private final ClosableLock lock = ClosableLock.reentrant();
		
		private       State        state = State.NEW;
		private final Key<T>       key;
		private       Class<T>     impl;
		private       MethodHandle dataConstructor;
		
		public ImplNode(Key<T> key){
			this.key = key;
		}
		
		private void init(Result<T> result){
			this.impl = result.impl;
			if(result.oOrderedFields.isPresent()){
				var ordered = result.oOrderedFields.get();
				
				try{
					var ctr = impl.getConstructor(ordered.stream().map(f -> Utils.typeToRaw(f.type)).toArray(Class[]::new));
					dataConstructor = Access.makeMethodHandle(ctr);
				}catch(NoSuchMethodException e){
					throw new ShouldNeverHappenError(e);
				}
			}
		}
	}
	
	private static final List<Class<?>> IGNORE_TYPES =
		Stream.concat(
			Stream.<Class<?>>iterate(
				IOInstance.Def.class,
				Objects::nonNull,
				cl -> Stream.of(cl.getInterfaces())
				            .findAny()
				            .orElse(null)),
			Stream.of(Object.class)
		).toList();
	
	private static final ConcurrentHashMap<Key<?>, ImplNode<?>> CACHE = new ConcurrentHashMap<>();
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance.Def<T>> Optional<Class<T>> unmap(Class<?> impl){
		Objects.requireNonNull(impl);
		if(!UtilL.instanceOf(impl, IOInstance.Def.class)) throw new IllegalArgumentException(impl.getName() + "");
		var clazz = impl;
		do{
			for(Class<?> interf : clazz.getInterfaces()){
				if(interf.getName().endsWith(IMPL_COMPLETION_POSTFIX)){
					try(var ignored = COMPLETION_LOCK.read()){
						var i      = interf;
						var unfull = COMPLETION_CACHE.entrySet().stream().filter(e -> e.getValue() == i).findAny().map(Map.Entry::getKey);
						if(unfull.isPresent()){
							interf = unfull.get();
						}
					}
				}
				//noinspection rawtypes
				var node = CACHE.get(new Key(interf));
				if(node != null && node.impl == impl){
					return Optional.of((Class<T>)node.key.clazz);
				}
			}
			clazz = clazz.getSuperclass();
		}while(clazz != null);
		return Optional.empty();
	}
	
	public static <T extends IOInstance<T>> MethodHandle dataConstructor(Class<T> interf){
		var key = new Key<>(interf);
		@SuppressWarnings("unchecked")
		var node = (ImplNode<T>)CACHE.get(key);
		if(node == null || node.state != ImplNode.State.DONE) node = getNode(key);
		var ctr = node.dataConstructor;
		if(ctr == null) fail(interf);
		return ctr;
	}
	
	private static <T extends IOInstance<T>> void fail(Class<T> interf){
		throw new RuntimeException("Please add " + IOInstance.Def.Order.class.getName() + " to " + interf.getName());
	}
	
	public static <T extends IOInstance<T>> Class<T> getImpl(Class<T> interf){
		return getNode(new Key<>(interf)).impl;
	}
	
	
	public static <T extends IOInstance.Def<T>> Class<T> getImplPartial(Key<T> key){
		if(key.includeNames.isEmpty()) throw new IllegalArgumentException("Names can not be empty");
		return getNode(key).impl;
	}
	
	private static <T extends IOInstance<T>> ImplNode<T> getNode(Key<T> key){
		Class<T> interf = key.clazz;
		if(!IOInstance.Def.isDefinition(interf)){
			throw new IllegalArgumentException(interf + " type must be an IOInstance.Def");
		}
		
		@SuppressWarnings("unchecked")
		var node = (ImplNode<T>)CACHE.computeIfAbsent(key, i -> new ImplNode<>((Key<T>)i));
		
		try(var ignored = node.lock.open()){
			switch(node.state){
				case null -> throw new ShouldNeverHappenError();
				case NEW -> { }
				case COMPILING -> throw new MalformedStruct("Type requires itself to compile");
				case DONE -> { return node; }
			}
			
			compileNode(node);
			
			StagedInit.runBaseStageTask(() -> {
				try{
					//Eagerly load struct
					Struct.of(node.impl).waitForStateDone();
				}catch(Throwable e){
					var e1 = StagedInit.WaitException.unwait(e);
					Log.warn("Failed to preload {}. Cause: {}", node.impl.getName(), e1.getMessage());
				}
			});
			
			return node;
		}catch(Throwable e){
			node.state = ImplNode.State.NEW;
			throw e;
		}
	}
	
	private static <T extends IOInstance<T>> void compileNode(ImplNode<T> node){
		node.state = ImplNode.State.COMPILING;
		
		Key<T> key = node.key;
		
		var inter = key.clazz;
		var hash  = inter.getClassLoader().hashCode();
		Log.trace(
			"Generating implementation of: {}#cyan{}#cyanBright fields: {} - {}", () -> {
				var cols = List.of(BLACK, RED, GREEN, YELLOW, BLUE, PURPLE, CYAN, WHITE);
				return List.of(
					inter.getName().substring(0, inter.getName().length() - inter.getSimpleName().length()),
					inter.getSimpleName(),
					((Optional<Object>)(Object)node.key.includeNames).orElse("<ALL>"),
					cols.get((int)(Integer.toUnsignedLong(hash)%cols.size())) + Integer.toHexString(hash) + RESET
				);
			}
		);
		
		var humanName = inter.getSimpleName();
		var compiled  = compile(key.complete(), humanName);
		
		node.init(compiled);
		
		node.state = ImplNode.State.DONE;
	}
	
	private static <T extends IOInstance<T>> Result<T> compile(Key<T> key, String humanName){
		Class<T> completeInter = key.clazz;
		
		var getters  = new ArrayList<FieldStub>();
		var setters  = new ArrayList<FieldStub>();
		var specials = collectMethods(completeInter, getters, setters);
		
		checkStyles(getters, setters);
		
		var fieldInfo = mergeStubs(getters, setters);
		
		key.includeNames.ifPresent(strings -> checkIncludeNames(fieldInfo, strings));
		
		var orderedFields = getOrder(completeInter, fieldInfo)
			                    .map(names -> names.stream()
			                                       .map(name -> fieldInfo.stream().filter(f -> f.name.equals(name)).findAny())
			                                       .map(Optional::orElseThrow)
			                                       .toList())
			                    .or(() -> fieldInfo.size()>1? Optional.empty() : Optional.of(fieldInfo));
		
		checkClass(completeInter, specials, orderedFields);
		
		checkTypes(fieldInfo);
		checkAnnotations(fieldInfo);
		checkModel(fieldInfo);
		
		try{
			var impl = generateImpl(key, specials, fieldInfo, orderedFields, humanName);
			return new Result<>(impl, orderedFields);
		}catch(Throwable e){
			if(EXIT_ON_FAIL){
				new RuntimeException("failed to compile implementation for " + key.clazz.getName(), e).printStackTrace();
				System.exit(1);
			}
			throw e;
		}
	}
	
	private static final Map<Class<?>, Class<?>> COMPLETION_CACHE = new HashMap<>();
	private static final ReadWriteClosableLock   COMPLETION_LOCK  = ReadWriteClosableLock.reentrant();
	
	@SuppressWarnings("unchecked")
	private static <T extends IOInstance<T>> Class<T> completeCached(Class<T> interf){
		try(var ignored = COMPLETION_LOCK.read()){
			var cached = COMPLETION_CACHE.get(interf);
			if(cached != null) return (Class<T>)cached;
		}
		
		try(var ignored = COMPLETION_LOCK.write()){
			var cached = COMPLETION_CACHE.get(interf);
			if(cached != null) return (Class<T>)cached;
			
			Class<?> complete = complete(interf);
			COMPLETION_CACHE.put(interf, complete);
			return (Class<T>)complete;
		}
	}
	
	private static <T extends IOInstance<T>> Class<?> complete(Class<T> interf){
		
		var getters = new ArrayList<FieldStub>();
		var setters = new ArrayList<FieldStub>();
		collectMethods(interf, getters, setters);
		var style = checkStyles(getters, setters);
		
		var missingGetters = new HashSet<String>();
		var missingSetters = new HashSet<String>();
		
		Stream.concat(getters.stream(), setters.stream()).map(FieldStub::varName).distinct().forEach(name -> {
			if(getters.stream().noneMatch(s -> s.varName().equals(name))) missingGetters.add(name);
			if(setters.stream().noneMatch(s -> s.varName().equals(name))) missingSetters.add(name);
		});
		
		if(missingGetters.isEmpty() && missingSetters.isEmpty()){
			return interf;
		}
		
		Log.trace("Generating completion of {}#cyan - missing getters: {}, missing setters: {}", interf.getSimpleName(), missingGetters, missingSetters);
		
		var completionName = interf.getName() + IMPL_COMPLETION_POSTFIX;
		
		var log   = JorthLogger.make();
		var jorth = new Jorth(interf.getClassLoader(), log == null? null : log::log);
		try{
			
			try(var writer = jorth.writer()){
				writeAnnotations(writer, Arrays.asList(interf.getAnnotations()));
				writer.addImportAs(completionName, "typ.impl");
				writer.write(
					"""
						implements {!}
						interface #typ.impl start
						""",
					interf.getName());
				
				for(String name : missingSetters){
					var setterName = switch(style){
						case NAMED -> "set" + TextUtil.firstToUpperCase(name);
						case RAW -> name;
					};
					var type = getters.stream().filter(s -> s.varName().equals(name)).findAny().map(FieldStub::type).orElseThrow();
					
					writer.write(
						"""
							function {!}
								arg arg1 {}
							end
							""",
						setterName,
						type
					);
				}
				for(String name : missingGetters){
					var getterName = switch(style){
						case NAMED -> "get" + TextUtil.firstToUpperCase(name);
						case RAW -> name;
					};
					var type = setters.stream().filter(s -> s.varName().equals(name)).findAny().map(FieldStub::type).orElseThrow();
					writer.write(
						"""
							function {!}
								{} returns
							end
							""",
						type,
						getterName
					);
				}
				writer.write("end");
			}
			
			var file = jorth.getClassFile(completionName);
			
			if(log != null){
				Log.log("Generated jorth:\n" + log.output());
				BytecodeUtils.printClass(file);
			}
			return Access.privateLookupIn(interf).defineClass(file);
		}catch(IllegalAccessException|MalformedJorth e){
			throw new RuntimeException(e);
		}
	}
	
	private static void checkIncludeNames(List<FieldInfo> fieldInfo, Set<String> includeNames){
		for(String includeName : includeNames){
			if(fieldInfo.stream().map(FieldInfo::name).noneMatch(includeName::equals)){
				throw new IllegalArgumentException(includeName + " is not a valid field name. \n" +
				                                   "Field names: " + fieldInfo.stream().map(FieldInfo::name).collect(Collectors.joining(", ")));
			}
		}
	}
	
	private static <T extends IOInstance<T>> void checkClass(Class<T> interf, Specials specials, Optional<List<FieldInfo>> oOrderedFields){
		
		if(specials.set.isPresent()){
			var set = specials.set.get();
			
			if(oOrderedFields.isEmpty()){
				throw new MalformedTemplateStruct(interf.getName() + " has a full setter but no argument order. Please add " + IOInstance.Def.Order.class.getName() + " to the type");
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
	
	private static <T extends IOInstance<T>> Class<T> generateImpl(Key<T> key, Specials specials, List<FieldInfo> fieldInfo, Optional<List<FieldInfo>> orderedFields, String humanName){
		var interf = key.clazz;
		var names  = key.includeNames;
		
		var implName = interf.getName() + IOInstance.Def.IMPL_NAME_POSTFIX + names.map(n -> n.stream().collect(Collectors.joining("_", "€€fields~", ""))).orElse("");
		
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
				
				writer.write(
					"""
						implements #typ.interf
						extends #IOInstance.Managed <#typ.impl>
						public class #typ.impl start
						""");
				
				for(var info : fieldInfo){
					if(isFieldIncluded(key, info.name)){
						defineField(writer, info);
						implementUserAccess(writer, info);
					}else{
						defineNoField(writer, info);
					}
				}
				
				defineStatics(writer);
				
				var includedFields  = key.includeNames().map(include -> fieldInfo.stream().filter(f -> include.contains(f.name)).toList()).orElse(fieldInfo);
				var includedOrdered = key.includeNames().map(include -> orderedFields.map(o -> o.stream().filter(f -> include.contains(f.name)).toList())).orElse(orderedFields);
				
				generateDefaultConstructor(writer, includedFields);
				generateDataConstructor(writer, orderedFields, key.includeNames);
				if(key.includeNames.isPresent()){
					generateDataConstructor(writer, includedOrdered, key.includeNames);
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
						if(info.type == ChunkPointer.class){
							nullCheck(writer);
						}
						writer.write("set this {!}", info.name);
					}
					writer.write("end");
				}
				
				generateSpecialToString(interf, writer, specials);
				
				stringSaga:
				{
					var format = interf.getAnnotation(IOInstance.Def.ToString.Format.class);
					if(format != null){
						generateFormatToString(key, includedFields, specials, writer, format, humanName);
						break stringSaga;
					}
					
					var toStrAnn = interf.getAnnotation(IOInstance.Def.ToString.class);
					if(toStrAnn == null && key.includeNames.isPresent()){
						toStrAnn = IOFieldTools.makeAnnotation(IOInstance.Def.ToString.class);
					}
					if(toStrAnn != null){
						generateStandardToString(key, includedOrdered.orElse(includedFields), specials, writer, toStrAnn);
						break stringSaga;
					}
				}
				writer.write("end");
			}
			
			var file = jorth.getClassFile(implName);
			if(log != null){
				Log.log(log.output());
				BytecodeUtils.printClass(file);
			}
			//noinspection unchecked
			return (Class<T>)Access.privateLookupIn(interf).defineClass(file);
			
		}catch(IllegalAccessException|MalformedJorth e){
			throw new RuntimeException(e);
		}
	}
	
	private static <T extends IOInstance<T>> Boolean isFieldIncluded(Key<T> key, String name){
		return key.includeNames.map(ns -> ns.contains(name)).orElse(true);
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
				public visibility
				function {!0}
					returns #String
				start
					get this this
					access static call {!1} {!0} start end
				end
				""",
			method.getName(),
			interf.getName());
	}
	
	private static <T extends IOInstance<T>> void generateFormatToString(Key<T> interf, List<FieldInfo> fieldInfo, Specials specials, CodeStream writer, IOInstance.Def.ToString.Format format, String humanName) throws MalformedJorth{
		var fragment = ToStringFormat.parse(format.value(), fieldInfo.stream().map(FieldInfo::name).toList());
		
		if(specials.toStr.isEmpty()){
			generateFormatToString(interf, fieldInfo, "toString", true, fragment, writer, humanName);
		}
		if(specials.toShortStr.isEmpty()){
			generateFormatToString(interf, fieldInfo, "toShortString", false, fragment, writer, humanName);
		}
	}
	
	private static <T extends IOInstance<T>> void generateStandardToString(Key<T> key, List<FieldInfo> fieldInfo, Specials specials, CodeStream writer, IOInstance.Def.ToString toStrAnn) throws MalformedJorth{
		
		if(specials.toStr.isEmpty()){
			generateStandardToString(key, toStrAnn, "toString", fieldInfo, writer);
		}
		if(specials.toShortStr.isEmpty()){
			if(toStrAnn.name()){
				generateStandardToString(key, IOFieldTools.makeAnnotation(IOInstance.Def.ToString.class, Map.of(
					"name", false,
					"curly", toStrAnn.curly(),
					"fNames", toStrAnn.fNames(),
					"filter", toStrAnn.filter()
				)), "toShortString", fieldInfo, writer);
			}else{
				writer.write(
					"""
						public function toShortString
							returns #String
						start
							get this this
							call toString
						end
						""");
			}
		}
	}
	
	private static void generateFormatToString(Key<?> key, List<FieldInfo> fieldInfo, String name, boolean all, ToStringFormat.ToStringFragment fragment, CodeStream writer, String humanName) throws MalformedJorth{
		
		writer.write(
			"""
				public function {!}
					returns #String
				 start
					new #StringBuilder
				""",
			name
		);
		
		List<ToStringFormat.ToStringFragment> compact = new ArrayList<>();
		processFragments(key.clazz, fragment, all, frag -> {
			if(frag instanceof FieldValue v && !isFieldIncluded(key, v.name())){
				frag = new Literal("<no " + v.name() + ">");
			}
			if(compact.isEmpty()){
				compact.add(frag);
				return;
			}
			if(frag instanceof Literal l2 && compact.get(compact.size() - 1) instanceof Literal l1){
				compact.set(compact.size() - 1, new Literal(l1.value() + l2.value()));
				return;
			}
			compact.add(frag);
		}, humanName);
		
		executeStringFragment(key.clazz, fieldInfo, new Concat(compact), all, writer, humanName);
		
		writer.write(
			"""
					call toString
				end
				""");
	}
	
	private static void processFragments(Class<?> interf, ToStringFormat.ToStringFragment fragment, boolean all, Consumer<ToStringFormat.ToStringFragment> out, String humanName) throws MalformedJorth{
		switch(fragment){
			case NOOP ignored -> { }
			case Concat f -> {
				for(var child : f.fragments()){
					processFragments(interf, child, all, out, humanName);
				}
			}
			case Literal f -> out.accept(f);
			case SpecialValue f -> {
				switch(f.value()){
					case CLASS_NAME -> out.accept(new Literal(humanName));
					case null -> throw new NullPointerException();
				}
			}
			case FieldValue frag -> out.accept(frag);
			case OptionalBlock f -> {
				if(all){
					processFragments(interf, f.content(), true, out, humanName);
				}
			}
		}
	}
	
	private static void executeStringFragment(
		Class<?> interf, List<FieldInfo> fieldInfo, ToStringFormat.ToStringFragment fragment,
		boolean all, CodeStream writer, String humanName
	) throws MalformedJorth{
		switch(fragment){
			case NOOP ignored -> { }
			case Concat f -> {
				for(var child : f.fragments()){
					executeStringFragment(interf, fieldInfo, child, all, writer, humanName);
				}
			}
			case Literal f -> append(writer, w -> w.write("'{}'", f.value()));
			case SpecialValue f -> {
				switch(f.value()){
					case CLASS_NAME -> append(writer, w -> w.write("'{}'", humanName));
					case null -> throw new NullPointerException();
				}
			}
			case FieldValue frag -> {
				var field = fieldInfo.stream().filter(n -> n.name.equals(frag.name())).findFirst().orElseThrow();
				append(writer, w -> w.write(
					"""
						static call #String valueOf start
							get this {!}
						end
						""", field.name));
			}
			case OptionalBlock f -> {
				if(all){
					executeStringFragment(interf, fieldInfo, f.content(), true, writer, humanName);
				}
			}
		}
	}
	
	private static void generateStandardToString(Key<?> key, IOInstance.Def.ToString toStrAnn, String name, List<FieldInfo> fieldInfo, CodeStream writer) throws MalformedJorth{
		
		writer.write(
			"""
				public function {!}
					returns #String
				start
					new #StringBuilder
				""",
			name
		);
		
		if(toStrAnn.name()){
			var nam = key.clazz.getSimpleName();
			if(nam.endsWith(IMPL_COMPLETION_POSTFIX)) nam = nam.substring(0, nam.length() - IMPL_COMPLETION_POSTFIX.length());
			var clean = nam;
			append(writer, w -> w.write("'{}'", clean));
		}
		if(toStrAnn.curly()){
			append(writer, w -> w.write("'{'"));
		}
		
		var     filter = Arrays.asList(toStrAnn.filter());
		boolean first  = true;
		for(FieldInfo info : fieldInfo){
			if(!filter.isEmpty() && !filter.contains(info.name)){
				continue;
			}
			if(!isFieldIncluded(key, info.name)){
				continue;
			}
			
			if(!first){
				append(writer, w -> w.write("', '"));
			}
			first = false;
			
			if(toStrAnn.fNames()){
				append(writer, w -> w.write("'{}: '", info.name));
			}
			
			append(writer, w -> {
				w.write(
					"""
						static call #String valueOf start
							get this {!}
						end
						""",
					info.name
				);
			});
		}
		
		if(toStrAnn.curly()){
			append(writer, w -> w.write("'}'"));
		}
		
		writer.write(
			"""
					call toString
				end
				""");
	}
	
	private static void append(CodeStream writer, UnsafeConsumer<CodeStream, MalformedJorth> val) throws MalformedJorth{
		writer.write(
			"""
				call append start
				""");
		val.accept(writer);
		writer.write("end");
	}
	
	private static void generateDefaultConstructor(CodeStream writer, List<FieldInfo> fieldInfo) throws MalformedJorth{
		writer.write(
			"""
				public function <init>
				start
					super start
						get #typ.impl $STRUCT
					end
				""");
		
		for(FieldInfo info : fieldInfo){
			if(info.type != ChunkPointer.class) continue;
			writer.write("get #ChunkPointer NULL");
			writer.write("set this {!}", info.name);
		}
		writer.write("end");
	}
	
	private static void generateDataConstructor(CodeStream writer, Optional<List<FieldInfo>> oOrderedFields, Optional<Set<String>> includeNames) throws MalformedJorth{
		
		if(oOrderedFields.isPresent()){
			var orderedFields = oOrderedFields.get();
			
			var part = writer.codePart();
			writer.write("public function <init>");
			for(int i = 0; i<orderedFields.size(); i++){
				FieldInfo info    = orderedFields.get(i);
				var       argName = "arg" + i;
				writer.write("arg {!} {}", argName, info.type);
			}
			writer.write("start");
			part.close();
			
			writer.write(
				"""
					super start
						get #typ.impl $STRUCT
					end
					""");
			
			for(int i = 0; i<orderedFields.size(); i++){
				FieldInfo info     = orderedFields.get(i);
				boolean   included = includeNames.map(in -> in.contains(info.name)).orElse(true);
				
				boolean nullCheck;
				if(info.type instanceof Class<?> c && c.isPrimitive()) nullCheck = false;
				else nullCheck = info.type == ChunkPointer.class ||
				                 info.annotations.stream()
				                                 .filter(a -> a instanceof IONullability)
				                                 .findAny()
				                                 .map(a -> ((IONullability)a).value())
				                                 .orElse(NOT_NULL) == NOT_NULL;
				
				if(nullCheck && !included){
					writer.write(
						"""
							static call #Objects requireNonNull start
								get #arg arg{}
							end
							pop
							""", i);
					continue;
				}
				
				if(included || nullCheck){
					writer.write("get #arg arg{}", i);
				}
				
				if(nullCheck){
					nullCheck(writer);
				}
				if(!included){
					continue;
				}
				writer.write("set this {!}", info.name);
			}
			writer.write("end");
		}
	}
	
	private static Optional<Class<?>> upperSame(Class<?> interf){
		
		var its = Arrays.stream(interf.getInterfaces()).filter(IOInstance.Def::isDefinition).toList();
		if(its.size() != 1) return Optional.empty();
		
		Set<String> parentNames = collectNames(its.get(0));
		Set<String> thisNames   = collectNames(interf);
		if(parentNames.equals(thisNames)){
			return Optional.of(its.get(0));
		}
		return Optional.empty();
	}
	private static Set<String> collectNames(Class<?> its){
		var getters = new ArrayList<FieldStub>();
		var setters = new ArrayList<FieldStub>();
		//noinspection unchecked,rawtypes
		collectMethods((Class<IOInstance>)its, getters, setters);
		return Stream.concat(getters.stream(), setters.stream()).map(FieldStub::varName).collect(Collectors.toSet());
	}
	
	private static Optional<List<String>> getOrder(Class<?> interf, List<FieldInfo> fieldInfo){
		var order = interf.getAnnotation(IOInstance.Def.Order.class);
		if(order == null){
			var its = Arrays.stream(interf.getInterfaces()).filter(IOInstance.Def::isDefinition).toList();
			if(its.size() != 1) return Optional.empty();
			
			var upper = upperSame(interf);
			return upper.map(u -> getOrder(u, fieldInfo)).filter(Optional::isPresent).map(Optional::get);
		}
		
		var check   = fieldInfo.stream().map(FieldInfo::name).collect(Collectors.toSet());
		var ordered = List.of(order.value());
		
		for(String name : ordered){
			if(check.remove(name)) continue;
			throw new MalformedTemplateStruct(
				name + " does not exist in " + interf.getName() + ".\n" +
				"Existing field names: " + fieldInfo.stream().map(FieldInfo::name).toList()
			);
		}
		
		if(!check.isEmpty()){
			throw new MalformedTemplateStruct(check + " are not listed in the order annotation");
		}
		
		return Optional.of(ordered);
	}
	
	private static void defineStatics(CodeStream writer) throws MalformedJorth{
		writer.write(
			"""
				private static final field $STRUCT #Struct
				
				function <clinit> start
					static call #Struct of start
						class #typ.impl
					end
					set #typ.impl $STRUCT
				end
				""");
	}
	
	private static void implementUserAccess(CodeStream writer, FieldInfo info) throws MalformedJorth{
		
		var getterName = info.getter.map(s -> s.method().getName()).orElseGet(() -> {
			var setter = info.setter.orElseThrow();
			return switch(setter.style()){
				case NAMED -> "get" + TextUtil.firstToUpperCase(setter.varName());
				case RAW -> setter.varName();
			};
		});
		var setterName = info.setter.map(s -> s.method().getName()).orElseGet(() -> {
			var setter = info.getter.orElseThrow();
			return switch(setter.style()){
				case NAMED -> "get" + TextUtil.firstToUpperCase(setter.varName());
				case RAW -> setter.varName();
			};
		});
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
		
		writer.write(
			"""
				@ #IOValue start name '{!2}' end
				@ #Override
				public function {!0}
					arg arg1 {1}
				start
					get #arg arg1
				""",
			setterName,
			info.type,
			info.name
		);
		
		if(info.type == ChunkPointer.class){
			nullCheck(writer);
		}
		
		writer.write(
			"""
					set this {!}
				end
				""",
			info.name
		);
	}
	
	private static void defineNoField(CodeStream writer, FieldInfo info) throws MalformedJorth{
		var getterName = info.getter.map(v -> v.method().getName()).orElseGet(() -> "get" + TextUtil.firstToUpperCase(info.name));
		var setterName = info.setter.map(v -> v.method().getName()).orElseGet(() -> "set" + TextUtil.firstToUpperCase(info.name));
		
		var anns = new ArrayList<>(info.annotations);
		anns.removeIf(a -> (a instanceof IOValue v && v.name().isEmpty()) || a instanceof Override);
		
		var valAnn = anns.stream().filter(a -> a instanceof IOValue).findAny().orElseGet(() -> IOFieldTools.makeAnnotation(IOValue.class, Map.of("name", info.name)));
		
		if(anns.stream().noneMatch(a -> a instanceof IOValue)) anns.add(valAnn);
		
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
			"private field {!} {}",
			info.name,
			info.type
		);
	}
	
	private static void writeAnnotations(CodeStream writer, List<Annotation> annotations) throws MalformedJorth{
		Set<Class<?>> annTypes = new HashSet<>();
		for(var ann : annotations){
			if(!annTypes.add(ann.annotationType())) continue;
			
			var part = writer.codePart();
			writer.write("@ {!} start", ann.annotationType().getName());
			
			scanAnnotation(ann, (name, value) -> {
				writer.write("{!} {}", name, switch(value){
					case null -> "null";
					case String s -> "'" + s.replace("'", "\\'") + "'";
					case Enum<?> e -> e.name();
					case Boolean v -> v.toString();
					case Class<?> c -> c.getName();
					case Number n -> {
						if(SupportedPrimitive.isAny(n.getClass())) yield n + "";
						throw new UnsupportedOperationException();
					}
					case String[] strs -> Arrays.stream(strs).map(s -> "'" + s.replace("'", "\\'") + "'").collect(Collectors.joining(" ", "[", "]"));
					default -> throw new NotImplementedException(value.getClass() + "");
				});
			});
			writer.write("end");
			part.close();
		}
	}
	
	private static void nullCheck(CodeStream writer) throws MalformedJorth{
		writer.write(
			"""
				static call #Objects requireNonNull start
					dup
				end
				pop
				""");
	}
	
	private static void scanAnnotation(Annotation ann, UnsafeBiConsumer<String, Object, MalformedJorth> entry) throws MalformedJorth{
		
		var c = ann.getClass();
		for(Method m : ann.annotationType().getMethods()){
			if(m.getParameterCount() != 0) continue;
			if(isStatic(m.getModifiers())) continue;
			
			if(m.getName().equals("annotationType")) continue;
			
			try{
				c.getSuperclass().getMethod(m.getName());
				continue;
			}catch(NoSuchMethodException ignored){ }
			Object val;
			try{
				m.setAccessible(true);
				val = m.invoke(ann);
			}catch(Throwable e){
				throw new RuntimeException(e);
			}
			entry.accept(m.getName(), val);
		}
	}
	
	private static <T extends IOInstance<T>> Specials collectMethods(Class<T> interf, List<FieldStub> getters, List<FieldStub> setters){
		var set        = Optional.<Method>empty();
		var toStr      = Optional.<Method>empty();
		var toShortStr = Optional.<Method>empty();
		
		var q = new ArrayDeque<Class<?>>(2);
		q.addLast(interf);
		var last = new ArrayList<Class<?>>(2);
		while(!q.isEmpty()){
			var clazz = q.removeFirst();
			last.add(clazz);
			
			toStr = toStr.or(() -> Arrays.stream(clazz.getMethods()).filter(m -> isToString(clazz, m, "toString")).findAny());
			toShortStr = toShortStr.or(() -> Arrays.stream(clazz.getMethods()).filter(m -> isToString(clazz, m, "toShortString")).findAny());
			if(toStr.isPresent() && toShortStr.isPresent()) break;
			
			if(q.isEmpty()){
				last.stream()
				    .flatMap(t -> Arrays.stream(t.getInterfaces()))
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
			
			var getter = CompilationTools.asGetterStub(method);
			var setter = CompilationTools.asSetterStub(method);
			
			getter.ifPresent(getters::add);
			setter.ifPresent(setters::add);
			
			if(getter.or(() -> setter).isEmpty()){
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
		return Stream.concat(getters.stream(), setters.stream())
		             .map(FieldStub::varName)
		             .distinct()
		             .map(name -> {
			             var getter = getters.stream().filter(s -> s.varName().equals(name)).findAny();
			             var setter = setters.stream().filter(s -> s.varName().equals(name)).findAny();
			
			             var gors = getter.or(() -> setter).orElseThrow();
			
			             var type = gors.type();
			
			             var anns = Stream.concat(getter.stream(), setter.stream())
			                              .map(f -> f.method().getAnnotations())
			                              .flatMap(Arrays::stream)
			                              .filter(a -> FieldCompiler.ANNOTATION_TYPES.contains(a.annotationType()))
			                              .collect(Collectors.toList());
			
			             IOValue valBack = null;
			             var     iter    = anns.iterator();
			             while(iter.hasNext()){
				             var ann = iter.next();
				             if(ann instanceof IOValue val){
					             if(val.name().equals("")){
						             valBack = val;
						             iter.remove();
						             continue;
					             }
					             throw new MalformedTemplateStruct(gors.varName() + ": @IOValue can not contain a name");
				             }
			             }
			             if(valBack == null) valBack = IOFieldTools.makeAnnotation(IOValue.class);
			             anns.add(valBack);
			
			             return new FieldInfo(name, type, anns, getter, setter);
		             })
		             .toList();
	}
	
	private static Style checkStyles(List<FieldStub> getters, List<FieldStub> setters){
		Map<Style, List<FieldStub>> styles = Stream.concat(getters.stream(), setters.stream()).collect(Collectors.groupingBy(FieldStub::style));
		
		if(styles.size()>1){
			var style = styles.entrySet().stream().reduce((a, b) -> a.getValue().size()>b.getValue().size()? a : b).map(Map.Entry::getKey).orElseThrow();
			throw new MalformedTemplateStruct(
				"Inconsistent getter/setter styles!\n" +
				"Style patterns:\n" + styles.keySet().stream().map(s -> "\t" + s + ":\t" + s.humanPattern).collect(Collectors.joining("\n")) + "\n" +
				"Most common style is:\n" + style + "\n" +
				"Bad styles:\n" +
				styles.entrySet()
				      .stream()
				      .filter(e -> e.getKey() != style)
				      .map(Map.Entry::getValue)
				      .flatMap(Collection::stream)
				      .map(s -> "\t" + s.method().getName() + ":\t" + s.style())
				      .collect(Collectors.joining("\n"))
			);
		}
		return styles.keySet().iterator().next();
	}
	
	private static void checkAnnotations(List<FieldInfo> fields){
		var problems = fields.stream()
		                     .map(gs -> {
			                          var dup = gs.stubs()
			                                      .flatMap(g -> Arrays.stream(g.method().getAnnotations()))
			                                      .filter(a -> FieldCompiler.ANNOTATION_TYPES.contains(a.annotationType()))
			                                      .collect(Collectors.groupingBy(Annotation::annotationType))
			                                      .values().stream()
			                                      .filter(l -> l.size()>1)
			                                      .map(l -> "\t\t" + l.get(0).annotationType().getName())
			                                      .collect(Collectors.joining("\n"));
			                          if(dup.isEmpty()) return "";
			                          return "\t" + gs.name + ":\n" + dup;
		                          }
		                     ).filter(s -> !s.isEmpty())
		                     .collect(Collectors.joining("\n"));
		
		if(!problems.isEmpty()){
			throw new MalformedTemplateStruct("Duplicate annotations:\n" + problems);
		}
	}
	
	private static void checkTypes(List<FieldInfo> fields){
		var problems = fields.stream()
		                     .filter(gs -> gs.stubs().collect(Collectors.groupingBy((FieldStub f) -> switch(f.type()){
			                     case Class<?> c -> c;
			                     case ParameterizedType p -> p;
			                     case TypeVariable<?> t -> Utils.extractFromVarType(t);
			                     default -> {
				                     var t = f.type();
				                     throw new NotImplementedException(t.getClass().getName());
			                     }
		                     })).size()>1)
		                     .map(gs -> "\t" + gs.name + ":\n" +
		                                "\t\t" + gs.stubs().map(g -> (g.style() != Style.RAW? g.method().getName() + ":\t" : "") + g.type() + "")
		                                           .collect(Collectors.joining("\n\t\t"))
		                     )
		                     .collect(Collectors.joining("\n"));
		
		if(!problems.isEmpty()){
			throw new MalformedTemplateStruct("Mismatched types:\n" + problems);
		}
	}
	
	private static void checkModel(List<FieldInfo> fieldInfo){
		var reg = FieldCompiler.registry();
		for(var field : fieldInfo){
			var ann = GetAnnotation.from(field.annotations);
			reg.requireCanCreate(FieldCompiler.getType(field.type, ann), ann);
		}
	}
}
