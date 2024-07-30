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
import com.lapissea.dfs.type.compilation.ToStringFormat.ToStringFragment;
import com.lapissea.dfs.type.compilation.ToStringFormat.ToStringFragment.Concat;
import com.lapissea.dfs.type.compilation.ToStringFormat.ToStringFragment.FieldValue;
import com.lapissea.dfs.type.compilation.ToStringFormat.ToStringFragment.Literal;
import com.lapissea.dfs.type.compilation.ToStringFormat.ToStringFragment.NOOP;
import com.lapissea.dfs.type.compilation.ToStringFormat.ToStringFragment.OptionalBlock;
import com.lapissea.dfs.type.compilation.ToStringFormat.ToStringFragment.SpecialValue;
import com.lapissea.dfs.type.field.Annotations;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.ClosableLock;
import com.lapissea.dfs.utils.ReadWriteClosableLock;
import com.lapissea.dfs.utils.WeakKeyValueMap;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.jorth.BytecodeUtils;
import com.lapissea.jorth.CodeStream;
import com.lapissea.jorth.Jorth;
import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.GenericDeclaration;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.lapissea.dfs.type.IOInstance.Def.IMPL_COMPLETION_POSTFIX;
import static com.lapissea.dfs.type.compilation.JorthUtils.writeAnnotations;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NOT_NULL;
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
	
	record CompletionInfo<T extends IOInstance<T>>(Class<T> base, Class<T> completed, Set<String> completedGetters, Set<String> completedSetters){
		
		private static final WeakKeyValueMap<Class<?>, CompletionInfo<?>> COMPLETION_CACHE = new WeakKeyValueMap<>();
		private static final ReadWriteClosableLock                        COMPLETION_LOCK  = ReadWriteClosableLock.reentrant();
		
		@SuppressWarnings("unchecked")
		private static <T extends IOInstance<T>> CompletionInfo<T> completeCached(Class<T> interf){
			var c = getCached(interf);
			if(c != null) return c;
			
			try(var ignored = COMPLETION_LOCK.write()){
				var cached = COMPLETION_CACHE.get(interf);
				if(cached != null) return (CompletionInfo<T>)cached;
				
				CompletionInfo<?> complete = complete(interf);
				COMPLETION_CACHE.put(interf, complete);
				return (CompletionInfo<T>)complete;
			}
		}
		
		@SuppressWarnings("unchecked")
		private static <T extends IOInstance<T>> CompletionInfo<T> getCached(Class<T> interf){
			try(var ignored = COMPLETION_LOCK.read()){
				var cached = COMPLETION_CACHE.get(interf);
				if(cached != null) return (CompletionInfo<T>)cached;
			}
			return null;
		}
		
		private static <T extends IOInstance<T>> CompletionInfo<?> complete(Class<T> interf){
			
			var getters = new ArrayList<FieldStub>();
			var setters = new ArrayList<FieldStub>();
			collectMethods(interf, getters, setters);
			var style = checkStyles(getters, setters);
			
			var missingGetters = new HashSet<String>();
			var missingSetters = new HashSet<String>();
			
			Iters.concat(getters, setters).map(FieldStub::varName).distinct().forEach(name -> {
				if(Iters.from(getters).noneMatch(s -> s.varName().equals(name))) missingGetters.add(name);
				if(Iters.from(setters).noneMatch(s -> s.varName().equals(name))) missingSetters.add(name);
			});
			
			if(missingGetters.isEmpty() && missingSetters.isEmpty()){
				return new CompletionInfo<>(interf, interf, Set.of(), Set.of());
			}
			
			
			Log.trace(
				"Generating completion of {}#cyan - {} {}",
				() -> List.of(interf.getSimpleName(),
				              missingGetters.isEmpty()? "" : "missing getters: " + missingGetters,
				              missingSetters.isEmpty()? "" : "missing setters: " + missingSetters
				));
			
			var getterMap = Iters.from(getters).toModMap(FieldStub::varName, Function.identity());
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
					
					for(String name : missingSetters){
						writer.write(
							"""
								function {!0}
									arg arg1 {1}
								end
								""",
							style.mapSetter(name),
							getterMap.get(name).type()
						);
					}
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
					writer.write("end");
				}
				
				var file = jorth.getClassFile(completionName);
				
				ClassGenerationCommons.dumpClassName(completionName, file);
				if(log != null){
					Log.log("Generated jorth:\n" + log.output());
					BytecodeUtils.printClass(file);
				}
				
				//noinspection unchecked
				var completed = (Class<T>)Access.privateLookupIn(interf).defineClass(file);
				return new CompletionInfo<>(interf, completed, Set.copyOf(missingGetters), Set.copyOf(missingSetters));
			}catch(IllegalAccessException|MalformedJorth e){
				throw new RuntimeException(e);
			}
		}
		
		@SuppressWarnings("unchecked")
		private static <T extends IOInstance.Def<T>> Optional<Class<T>> findSourceInterface(Class<?> impl){
			var clazz = impl;
			do{
				for(Class<?> interf : clazz.getInterfaces()){
					if(interf.getName().endsWith(IMPL_COMPLETION_POSTFIX)){
						try(var ignored = COMPLETION_LOCK.read()){
							var i      = interf;
							var unfull = COMPLETION_CACHE.iter().firstMatching(e -> e.getValue().completed == i).map(Map.Entry::getKey);
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
				List<FieldInfo> ordered = result.oOrderedFields.get();
				
				try{
					var ctr = impl.getConstructor(Iters.from(ordered).map(f -> Utils.typeToRaw(f.type)).toArray(Class[]::new));
					dataConstructor = Access.makeMethodHandle(ctr);
				}catch(NoSuchMethodException e){
					throw new ShouldNeverHappenError(e);
				}
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
		@SuppressWarnings("unchecked")
		var node = (ImplNode<T>)CACHE.get(key);
		if(node == null || node.state != ImplNode.State.DONE) node = getNode(key);
		var ctr = node.dataConstructor;
		if(ctr == null){
			throw new RuntimeException("Please add " + IOInstance.Def.Order.class.getName() + " to " + interf.getName());
		}
		return ctr;
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
		var hash  = inter.getClassLoader().hashCode();
		Log.trace(
			"Generating implementation of: {}#cyan{}#cyanBright {}classloader: {}", () -> {
				var cols = List.of(BLACK, RED, GREEN, YELLOW, BLUE, PURPLE, CYAN, WHITE);
				return List.of(
					inter.getName().substring(0, inter.getName().length() - inter.getSimpleName().length()),
					inter.getSimpleName(),
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
					return new Result<>(cls, orderedFields);
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
				
				for(var info : fieldInfo){
					if(isFieldIncluded(includeNames, info.name)){
						defineField(writer, info);
						implementUserAccess(writer, info, humanName);
					}else{
						defineNoField(writer, info);
					}
				}
				
				defineStatics(writer);
				
				
				var includedFields  = includeNames.map(include -> Iters.from(fieldInfo).filter(f -> include.contains(f.name)).toList()).orElse(fieldInfo);
				var includedOrdered = includeNames.map(include -> orderedFields.map(o -> Iters.from(o).filter(f -> include.contains(f.name)).toList())).orElse(orderedFields);
				
				generateDefaultConstructor(writer, includedFields);
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
					writer.write("end");
				}
				
				generateSpecialToString(interf, writer, specials);
				
				stringSaga:
				{
					var format = interf.getAnnotation(IOInstance.Def.ToString.Format.class);
					if(format != null){
						generateFormatToString(includeNames, includedFields, specials, writer, format, humanName);
						break stringSaga;
					}
					
					var toStrAnn = interf.getAnnotation(IOInstance.Def.ToString.class);
					if(toStrAnn == null && includeNames.isPresent()){
						toStrAnn = Annotations.make(IOInstance.Def.ToString.class);
					}
					if(toStrAnn != null){
						generateStandardToString(humanName, includeNames, includedOrdered.orElse(includedFields), specials, writer, toStrAnn);
						break stringSaga;
					}
				}
				writer.write("end");
			}
			
			var file = jorth.getClassFile(implName);
			ClassGenerationCommons.dumpClassName(implName, file);
			if(log != null){
				Log.log(log.output());
				BytecodeUtils.printClass(file);
			}
			
			//noinspection unchecked
			return (Class<T>)Access.privateLookupIn(interf).defineClass(file);
		}catch(IllegalAccessException|MalformedJorth e){
			throw new RuntimeException("Failed to generate implementation for: " + interf.getName(), e);
		}
	}
	
	private static <T extends IOInstance<T>> Boolean isFieldIncluded(Optional<Set<String>> includeNames, String name){
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
	
	private static <T extends IOInstance<T>> void generateFormatToString(Optional<Set<String>> includeNames, List<FieldInfo> fieldInfo, Specials specials, CodeStream writer, IOInstance.Def.ToString.Format format, String humanName) throws MalformedJorth{
		var fragment = ToStringFormat.parse(format.value(), Iters.from(fieldInfo).toModList(FieldInfo::name));
		
		if(specials.toStr.isEmpty()){
			generateFormatToString(includeNames, fieldInfo, "toString", true, fragment, writer, humanName);
		}
		if(specials.toShortStr.isEmpty()){
			generateFormatToString(includeNames, fieldInfo, "toShortString", false, fragment, writer, humanName);
		}
	}
	
	private static <T extends IOInstance<T>> void generateStandardToString(String classHumanName, Optional<Set<String>> includeNames, List<FieldInfo> fieldInfo, Specials specials, CodeStream writer, IOInstance.Def.ToString toStrAnn) throws MalformedJorth{
		
		if(specials.toStr.isEmpty()){
			generateStandardToString(classHumanName, includeNames, toStrAnn, "toString", fieldInfo, writer);
		}
		if(specials.toShortStr.isEmpty()){
			if(toStrAnn.name()){
				generateStandardToString(classHumanName, includeNames, Annotations.make(IOInstance.Def.ToString.class, Map.of(
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
	
	private static void generateFormatToString(Optional<Set<String>> includeNames, List<FieldInfo> fieldInfo, String name, boolean all, ToStringFragment fragment, CodeStream writer, String humanName) throws MalformedJorth{
		
		writer.write(
			"""
				public function {!}
					returns #String
				 start
					new #StringBuilder
				""",
			name
		);
		
		List<ToStringFragment> compact = new ArrayList<>();
		processFragments(fragment, all, frag -> {
			if(frag instanceof FieldValue v && !isFieldIncluded(includeNames, v.name())){
				frag = new Literal("<no " + v.name() + ">");
			}
			if(compact.isEmpty()){
				compact.add(frag);
				return;
			}
			if(frag instanceof Literal l2 && compact.getLast() instanceof Literal l1){
				compact.set(compact.size() - 1, new Literal(l1.value() + l2.value()));
				return;
			}
			compact.add(frag);
		}, humanName);
		
		executeStringFragment(fieldInfo, new Concat(compact), all, writer, humanName);
		
		writer.write(
			"""
					call toString
				end
				""");
	}
	
	private static void processFragments(ToStringFragment fragment, boolean all, Consumer<ToStringFragment> out, String humanName){
		switch(fragment){
			case NOOP ignored -> { }
			case Concat f -> {
				for(var child : f.fragments()){
					processFragments(child, all, out, humanName);
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
					processFragments(f.content(), true, out, humanName);
				}
			}
		}
	}
	
	private static void executeStringFragment(
		List<FieldInfo> fieldInfo, ToStringFragment fragment,
		boolean all, CodeStream writer, String humanName
	) throws MalformedJorth{
		switch(fragment){
			case NOOP ignored -> { }
			case Concat f -> {
				for(var child : f.fragments()){
					executeStringFragment(fieldInfo, child, all, writer, humanName);
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
				var field = Iters.from(fieldInfo).firstMatching(n -> n.name.equals(frag.name())).orElseThrow();
				append(writer, w -> w.write(
					"""
						static call #String valueOf start
							get this {!}
						end
						""", field.name));
			}
			case OptionalBlock f -> {
				if(all){
					executeStringFragment(fieldInfo, f.content(), true, writer, humanName);
				}
			}
		}
	}
	
	private static void generateStandardToString(String classHumanName, Optional<Set<String>> includeNames, IOInstance.Def.ToString toStrAnn, String name, List<FieldInfo> fieldInfo, CodeStream writer) throws MalformedJorth{
		
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
			append(writer, w -> w.write("'{}'", classHumanName));
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
			if(!isFieldIncluded(includeNames, info.name)){
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
			if(info.type == ChunkPointer.class){
				writer.write("get #ChunkPointer NULL");
				writer.write("set this {!}", info.name);
			}else if(Utils.typeToRaw(info.type) == Optional.class){
				writer.write("static call {} empty", Optional.class);
				writer.write("set this {!}", info.name);
			}
		}
		writer.write("end");
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
						get #typ.impl $STRUCT
					end
				""",
			Iters.rangeMap(0, orderedFields.size(), i -> new SimpleEntry<>("arg" + i, orderedFields.get(i).type)).asCollection()
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
		writer.write("end");
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
		var order = interf.getAnnotation(IOInstance.Def.Order.class);
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
	
	private static void implementUserAccess(CodeStream writer, FieldInfo info, String classHumanName) throws MalformedJorth{
		
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
			"private field {!} {}",
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
			
			CompilationTools.asStub(method).ifPresentOrElse(
				st -> (st.isGetter()? getters : setters).add(st),
				() -> { throw new MalformedTemplateStruct(method + " is not a setter or a getter!"); }
			);
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
		return styles.keySet().iterator().next();
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
