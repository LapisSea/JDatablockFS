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
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.jorth.JorthCompiler;
import com.lapissea.jorth.JorthWriter;
import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeBiConsumer;
import com.lapissea.util.function.UnsafeConsumer;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.ConsoleColors.*;
import static java.lang.reflect.Modifier.isStatic;

public class DefInstanceCompiler{
	
	private static final boolean PRINT_BYTECODE=GlobalConfig.configFlag("classGen.printBytecode", false);
	private static final boolean EXIT_ON_FAIL  =GlobalConfig.configFlag("classGen.exitOnFail", !GlobalConfig.RELEASE_MODE);
	
	private record Specials(
		Optional<Method> set,
		Optional<Method> toStr,
		Optional<Method> toShortStr
	){}
	
	private record FieldInfo(String name, Type type, List<Annotation> annotations, Optional<FieldStub> getter, Optional<FieldStub> setter){
		Stream<FieldStub> stubs(){return Stream.concat(getter.stream(), setter.stream());}
		@Override
		public String toString(){
			return "{"+
			       name+": "+Utils.typeToHuman(type, false)+
			       getter.map(v->" getter: "+v).orElse("")+
			       setter.map(v->" setter: "+v).orElse("")+
			       "}";
		}
	}
	
	public record Key<T extends IOInstance<T>>(Class<T> clazz, Optional<Set<String>> includeNames){
		public Key(Class<T> clazz){
			this(clazz, Optional.empty());
		}
		public Key(Class<T> clazz, Optional<Set<String>> includeNames){
			this.clazz=Objects.requireNonNull(clazz);
			this.includeNames=includeNames.map(Set::copyOf);
		}
	}
	
	private static final class ImplNode<T extends IOInstance<T>>{
		enum State{
			NEW, COMPILING, DONE
		}
		
		private final Lock lock=new ReentrantLock();
		
		private       State        state=State.NEW;
		private final Key<T>       key;
		private       Class<T>     impl;
		private       MethodHandle dataConstructor;
		
		public ImplNode(Key<T> key){
			this.key=key;
		}
		
		private void init(Class<T> impl, Optional<List<FieldInfo>> oOrderedFields){
			this.impl=impl;
			if(oOrderedFields.isPresent()){
				var ordered=oOrderedFields.get();
				
				try{
					var ctr=impl.getConstructor(ordered.stream().map(f->Utils.typeToRaw(f.type)).toArray(Class[]::new));
					dataConstructor=Access.makeMethodHandle(ctr);
				}catch(NoSuchMethodException e){
					throw new ShouldNeverHappenError(e);
				}
			}
		}
	}
	
	private static final List<Class<?>> IGNORE_TYPES=
		Stream.concat(
			Stream.<Class<?>>iterate(
				IOInstance.Def.class,
				Objects::nonNull,
				cl->Stream.of(cl.getInterfaces())
				          .findAny()
				          .orElse(null)),
			Stream.of(Object.class)
		).toList();
	
	private static final ConcurrentHashMap<Key<?>, ImplNode<?>> CACHE=new ConcurrentHashMap<>();
	
	public static boolean isDefinition(Class<?> clazz){
		return clazz.isInterface()&&UtilL.instanceOf(clazz, IOInstance.Def.class);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance.Def<T>> Class<T> unmap(Class<?> impl){
		Objects.requireNonNull(impl);
		if(!UtilL.instanceOf(impl, IOInstance.Def.class)) throw new IllegalArgumentException(impl.getName()+"");
		var clazz=impl;
		while(true){
			for(Class<?> interf : clazz.getInterfaces()){
				var node=CACHE.get(new Key(interf));
				if(node.impl==impl){
					return (Class<T>)node.key.clazz;
				}
			}
			clazz=clazz.getSuperclass();
			if(clazz==null) throw new ShouldNeverHappenError("???");
		}
	}
	
	public static <T extends IOInstance<T>> MethodHandle dataConstructor(Class<T> interf){
		var key=new Key<>(interf);
		@SuppressWarnings("unchecked")
		var node=(ImplNode<T>)CACHE.get(key);
		if(node==null||node.state!=ImplNode.State.DONE) node=getNode(key, false);
		var ctr=node.dataConstructor;
		if(ctr==null) fail(interf);
		return ctr;
	}
	
	private static <T extends IOInstance<T>> void fail(Class<T> interf){
		throw new RuntimeException("Please add "+IOInstance.Def.Order.class.getName()+" to "+interf.getName());
	}
	
	public static <T extends IOInstance<T>> Class<T> getImpl(Class<T> interf, boolean structNow){
		return getNode(new Key<>(interf), structNow).impl;
	}
	
	
	public static <T extends IOInstance.Def<T>> Class<T> getImplPartial(Key<T> key){
		if(key.includeNames.isEmpty()) throw new IllegalArgumentException("Names can not be empty");
		return getNode(key, false).impl;
	}
	
	private static <T extends IOInstance<T>> ImplNode<T> getNode(Key<T> key, boolean structNow){
		Class<T> interf=key.clazz;
		if(!isDefinition(interf)){
			throw new IllegalArgumentException(interf+" type must be an IOInstance.Def");
		}
		
		@SuppressWarnings("unchecked")
		var node=(ImplNode<T>)CACHE.computeIfAbsent(key, i->new ImplNode<>((Key<T>)i));
		
		node.lock.lock();
		try{
			switch(node.state){
				case null -> throw new ShouldNeverHappenError();
				case NEW -> {}
				case COMPILING -> throw new MalformedStruct("Type requires itself to compile");
				case DONE -> {return node;}
			}
			
			Class<T> impl=compile(node);
			
			try{
				//Eagerly load struct
				if(structNow) Struct.of(impl, StagedInit.STATE_DONE);
				else Struct.of(impl);
			}catch(Throwable e){
				Log.warn("Failed to preload {}. Cause: {}", impl.getName(), e.getMessage());
			}
			
			return node;
		}catch(Throwable e){
			node.state=ImplNode.State.NEW;
			throw e;
		}finally{
			node.lock.unlock();
		}
	}
	
	private static <T extends IOInstance<T>> Class<T> compile(ImplNode<T> node){
		node.state=ImplNode.State.COMPILING;
		
		var key   =node.key;
		var interf=key.clazz;
		
		var hash=interf.getClassLoader().hashCode();
		Log.trace("Generating implementation of: {} - {}", interf.getName(), (Supplier<String>)()->{
			var cols=List.of(BLACK, RED, GREEN, YELLOW, BLUE, PURPLE, CYAN, WHITE);
			return cols.get((int)(Integer.toUnsignedLong(hash)%cols.size()))+Integer.toHexString(hash)+RESET;
		});
		
		var getters =new ArrayList<FieldStub>();
		var setters =new ArrayList<FieldStub>();
		var specials=collectMethods(interf, getters, setters);
		
		checkStyles(getters, setters);
		
		var fieldInfo=mergeStubs(getters, setters);
		
		key.includeNames.ifPresent(strings->checkIncludeNames(fieldInfo, strings));
		
		var orderedFields=getOrder(interf, fieldInfo)
			                  .map(names->names.stream()
			                                   .map(name->fieldInfo.stream().filter(f->f.name.equals(name)).findAny())
			                                   .map(Optional::orElseThrow)
			                                   .toList())
			                  .or(()->fieldInfo.size()>1?Optional.empty():Optional.of(fieldInfo));
		
		checkClass(interf, specials, fieldInfo, orderedFields);
		
		checkTypes(fieldInfo);
		checkAnnotations(fieldInfo);
		checkModel(fieldInfo);
		
		Class<T> impl;
		try{
			impl=generateImpl(key, specials, fieldInfo, orderedFields);
		}catch(Throwable e){
			if(EXIT_ON_FAIL){
				new RuntimeException("failed to compile "+interf.getName(), e).printStackTrace();
				System.exit(1);
			}
			throw e;
		}
		
		node.init(impl, orderedFields);
		
		node.state=ImplNode.State.DONE;
		return impl;
	}
	
	private static void checkIncludeNames(List<FieldInfo> fieldInfo, Set<String> includeNames){
		for(String includeName : includeNames){
			if(fieldInfo.stream().map(FieldInfo::name).noneMatch(includeName::equals)){
				throw new IllegalArgumentException(includeName+" is not a valid field name. \n"+
				                                   "Field names: "+fieldInfo.stream().map(FieldInfo::name).collect(Collectors.joining(", ")));
			}
		}
	}
	
	private static <T extends IOInstance<T>> void checkClass(Class<T> interf, Specials specials, List<FieldInfo> fields, Optional<List<FieldInfo>> oOrderedFields){
		
		if(specials.set.isPresent()){
			var set=specials.set.get();
			
			if(oOrderedFields.isEmpty()){
				throw new MalformedTemplateStruct(interf.getName()+" has a full setter but no argument order. Please add "+IOInstance.Def.Order.class.getName()+" to the type");
			}
			
			var orderedFields=oOrderedFields.get();
			
			if(set.getParameterCount()!=orderedFields.size()){
				throw new MalformedTemplateStruct(set+" has "+set.getParameterCount()+" parameters but has "+orderedFields.size()+" fields");
			}
			
			var parms=set.getGenericParameterTypes();
			for(int i=0;i<orderedFields.size();i++){
				var field   =orderedFields.get(i);
				var parmType=parms[i];
				
				if(field.type.equals(parmType)) continue;
				
				//TODO: implement fits type instead of exact match
				throw new MalformedTemplateStruct(field.name+" has the type of "+field.type.getTypeName()+" but set argument is "+parmType);
			}
		}
	}
	
	private static <T extends IOInstance<T>> Class<T> generateImpl(Key<T> key, Specials specials, List<FieldInfo> fieldInfo, Optional<List<FieldInfo>> orderedFields){
		var interf  =key.clazz;
		var names   =key.includeNames;
		var implName=interf.getName()+IOInstance.Def.IMPL_NAME_POSTFIX+names.map(n->n.stream().collect(Collectors.joining("_", "€€fields~", ""))).orElse("");
		
		try{
			JorthCompiler jorth=new JorthCompiler(interf.getClassLoader());
			
			try(var writer=jorth.writeCode()){
				writer.write("#TOKEN(0) typ.impl                          define", implName);
				writer.write("#TOKEN(0) typ.interf                        define", interf.getName());
				writer.write("#TOKEN(0) typ.String                        define", String.class.getName());
				writer.write("#TOKEN(0) typ.Struct                        define", Struct.class.getName());
				writer.write("#TOKEN(0) typ.Objects                       define", Objects.class.getName());
				writer.write("#TOKEN(0) typ.Override                      define", Override.class.getName());
				writer.write("#TOKEN(0) typ.ChunkPointer                  define", ChunkPointer.class.getName());
				writer.write("#TOKEN(0) typ.StringBuilder                 define", StringBuilder.class.getName());
				writer.write("#TOKEN(0) typ.IOInstance.Managed            define", IOInstance.Managed.class.getName());
				writer.write("#TOKEN(0) typ.UnsupportedOperationException define", UnsupportedOperationException.class.getName());
				
				writer.write(
					"""
						public visibility
						typ.interf implements
						[typ.impl] typ.IOInstance.Managed extends
						typ.impl class start
						""");
				
				for(FieldInfo info : fieldInfo){
					if(isFieldIncluded(key, info.name)){
						defineField(writer, info);
						implementUserAccess(writer, info);
					}else{
						defineNoField(writer, info);
					}
				}
				
				defineStatics(writer);
				
				
				generateConstructors(fieldInfo, orderedFields, writer);
				
				if(specials.set.isPresent()){
					var set=specials.set.get();
					
					var ordered=orderedFields.orElseThrow();
					
					
					for(FieldInfo info : ordered){
						var type=Objects.requireNonNull(TypeLink.of(info.type));
						writer.write(
							"#RAW(0) #TOKEN(1) arg",
							JorthUtils.toJorthGeneric(type),
							info.name);
					}
					
					writer.write(
						"""
							public visibility
							#TOKEN(0) function start
							""",
						set.getName());
					
					for(FieldInfo info : ordered){
						writer.write("<arg> #TOKEN(0) get", info.name);
						if(info.type==ChunkPointer.class){
							nullCheck(writer);
						}
						writer.write("this #TOKEN(0) set", info.name);
					}
					writer.write("end");
				}
				
				generateSpecialToString(interf, writer, specials);
				
				stringSaga:
				{
					var format=interf.getAnnotation(IOInstance.Def.ToString.Format.class);
					if(format!=null){
						generateFormatToString(key, fieldInfo, specials, writer, format);
						break stringSaga;
					}
					
					var toStrAnn=interf.getAnnotation(IOInstance.Def.ToString.class);
					if(toStrAnn==null&&key.includeNames.isPresent()){
						toStrAnn=IOFieldTools.makeAnnotation(IOInstance.Def.ToString.class);
					}
					if(toStrAnn!=null){
						generateStandardToString(key, orderedFields.orElse(fieldInfo), specials, writer, toStrAnn);
						break stringSaga;
					}
				}
			}
			
			//noinspection unchecked
			return (Class<T>)Access.privateLookupIn(interf).defineClass(jorth.classBytecode(PRINT_BYTECODE));
			
		}catch(IllegalAccessException|MalformedJorthException e){
			throw new RuntimeException(e);
		}
	}
	
	private static <T extends IOInstance<T>> Boolean isFieldIncluded(Key<T> key, String name){
		return key.includeNames.map(ns->ns.contains(name)).orElse(true);
	}
	
	private static <T extends IOInstance<T>> void generateSpecialToString(Class<T> interf, JorthWriter writer, Specials specials) throws MalformedJorthException{
		if(specials.toStr.isPresent()){
			generateSpecialToString(interf, writer, specials.toStr.get());
		}
		if(specials.toShortStr.isPresent()){
			generateSpecialToString(interf, writer, specials.toShortStr.get());
		}
	}
	
	private static <T extends IOInstance<T>> void generateSpecialToString(Class<T> interf, JorthWriter writer, Method method) throws MalformedJorthException{
		writer.write(
			"""
				public visibility
				typ.String returns
				#TOKEN(0) function start
					this this get
					#TOKEN(1) #TOKEN(0) (1) static call
				end
				""",
			method.getName(),
			interf.getName());
	}
	
	private static <T extends IOInstance<T>> void generateFormatToString(Key<T> interf, List<FieldInfo> fieldInfo, Specials specials, JorthWriter writer, IOInstance.Def.ToString.Format format) throws MalformedJorthException{
		var fragment=ToStringFormat.parse(format.value(), fieldInfo.stream().map(FieldInfo::name).toList());
		
		if(specials.toStr.isEmpty()){
			generateFormatToString(interf, fieldInfo, "toString", true, fragment, writer);
		}
		if(specials.toShortStr.isEmpty()){
			generateFormatToString(interf, fieldInfo, "toShortString", false, fragment, writer);
		}
	}
	
	private static <T extends IOInstance<T>> void generateStandardToString(Key<T> key, List<FieldInfo> fieldInfo, Specials specials, JorthWriter writer, IOInstance.Def.ToString toStrAnn) throws MalformedJorthException{
		
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
						public visibility
						typ.String returns
						toShortString function start
							this this get
							toString (0) call
						end
						""");
			}
		}
	}
	
	private static void generateFormatToString(Key<?> key, List<FieldInfo> fieldInfo, String name, boolean all, ToStringFormat.ToStringFragment fragment, JorthWriter writer) throws MalformedJorthException{
		
		writer.write(
			"""
				public visibility
				typ.String returns
				#TOKEN(0) function start
					typ.StringBuilder (0) new
				""",
			name
		);
		
		List<ToStringFormat.ToStringFragment> compact=new ArrayList<>();
		processFragments(key.clazz, fragment, all, frag->{
			if(frag instanceof FieldValue v&&!isFieldIncluded(key, v.name())){
				frag=new Literal("<no "+v.name()+">");
			}
			if(compact.isEmpty()){
				compact.add(frag);
				return;
			}
			if(frag instanceof Literal l2&&compact.get(compact.size()-1) instanceof Literal l1){
				compact.set(compact.size()-1, new Literal(l1.value()+l2.value()));
				return;
			}
			compact.add(frag);
		});
		
		executeStringFragment(key.clazz, fieldInfo, new Concat(compact), all, writer);
		
		writer.write(
			"""
					toString (0) call
				end
				""");
	}
	
	private static void processFragments(Class<?> interf, ToStringFormat.ToStringFragment fragment, boolean all, Consumer<ToStringFormat.ToStringFragment> out) throws MalformedJorthException{
		switch(fragment){
			case NOOP ignored -> {}
			case Concat f -> {
				for(var child : f.fragments()){
					processFragments(interf, child, all, out);
				}
			}
			case Literal f -> out.accept(f);
			case SpecialValue f -> {
				switch(f.value()){
					case CLASS_NAME -> out.accept(new Literal(interf.getSimpleName()));
					case null -> throw new NullPointerException();
				}
			}
			case FieldValue frag -> out.accept(frag);
			case OptionalBlock f -> {
				if(all){
					processFragments(interf, f.content(), true, out);
				}
			}
		}
	}
	
	private static void executeStringFragment(Class<?> interf, List<FieldInfo> fieldInfo, ToStringFormat.ToStringFragment fragment, boolean all, JorthWriter writer) throws MalformedJorthException{
		switch(fragment){
			case NOOP ignored -> {}
			case Concat f -> {
				for(var child : f.fragments()){
					executeStringFragment(interf, fieldInfo, child, all, writer);
				}
			}
			case Literal f -> append(writer, w->w.write("'#RAW(0)'", f.value()));
			case SpecialValue f -> {
				switch(f.value()){
					case CLASS_NAME -> append(writer, w->w.write("'#RAW(0)'", interf.getSimpleName()));
					case null -> throw new NullPointerException();
				}
			}
			case FieldValue frag -> {
				var field=fieldInfo.stream().filter(n->n.name.equals(frag.name())).findFirst().orElseThrow();
				append(writer, w->w.write(
					"""
						this #TOKEN(0) get
						typ.String valueOf (1) static call
						""", field.name));
			}
			case OptionalBlock f -> {
				if(all){
					executeStringFragment(interf, fieldInfo, f.content(), true, writer);
				}
			}
		}
	}
	
	private static void generateStandardToString(Key<?> key, IOInstance.Def.ToString toStrAnn, String name, List<FieldInfo> fieldInfo, JorthWriter writer) throws MalformedJorthException{
		
		writer.write(
			"""
				public visibility
				typ.String returns
				#TOKEN(0) function start
					typ.StringBuilder (0) new
				""",
			name
		);
		
		if(toStrAnn.name()){
			append(writer, w->w.write("'#RAW(0)'", key.clazz.getSimpleName()));
		}
		if(toStrAnn.curly()){
			append(writer, w->w.write("'{'"));
		}
		
		var     filter=Arrays.asList(toStrAnn.filter());
		boolean first =true;
		for(FieldInfo info : fieldInfo){
			if(!filter.isEmpty()&&!filter.contains(info.name)){
				continue;
			}
			if(!isFieldIncluded(key, info.name)){
				continue;
			}
			
			if(!first){
				append(writer, w->w.write("', '"));
			}
			first=false;
			
			if(toStrAnn.fNames()){
				append(writer, w->w.write("'#RAW(0): '", info.name));
			}
			append(writer, w->w.write(
				"""
					this #TOKEN(0) get
					typ.String valueOf (1) static call
					""",
				info.name
			));
		}
		
		if(toStrAnn.curly()){
			append(writer, w->w.write("'}'"));
		}
		
		writer.write(
			"""
					toString (0) call
				end
				""");
	}
	
	private static void append(JorthWriter writer, UnsafeConsumer<JorthWriter, MalformedJorthException> val) throws MalformedJorthException{
		writer.write("dup");
		val.accept(writer);
		writer.write(
			"""
				append (1) call
				pop
				"""
		);
	}
	
	private static void generateConstructors(List<FieldInfo> fieldInfo, Optional<List<FieldInfo>> oOrderedFields, JorthWriter writer) throws MalformedJorthException{
		writer.write(
			"""
				public visibility
				<init> function start
					this this get
					typ.impl $STRUCT get
					super
				""");
		
		for(FieldInfo info : fieldInfo){
			if(info.type!=ChunkPointer.class) continue;
			writer.write("typ.ChunkPointer NULL get");
			writer.write("this").write(info.name).write("set");
		}
		writer.write("end");
		
		if(oOrderedFields.isPresent()){
			var orderedFields=oOrderedFields.get();
			
			writer.write("public visibility");
			for(int i=0;i<orderedFields.size();i++){
				FieldInfo info   =orderedFields.get(i);
				var       type   =JorthUtils.toJorthGeneric(Objects.requireNonNull(TypeLink.of(info.type)));
				var       argName="arg"+i;
				writer.write("#RAW(0) #TOKEN(1) arg", type, argName);
			}
			
			writer.write(
				"""
					<init> function start
						this this get
						typ.impl $STRUCT get
						super
					""");
			
			for(int i=0;i<orderedFields.size();i++){
				FieldInfo info=orderedFields.get(i);
				writer.write("<arg>").write("arg"+i).write("get");
				if(info.type==ChunkPointer.class){
					nullCheck(writer);
				}
				writer.write("this").write(info.name).write("set");
			}
			writer.write("end");
		}
	}
	
	private static Optional<List<String>> getOrder(Class<?> interf, List<FieldInfo> fieldInfo){
		var order=interf.getAnnotation(IOInstance.Def.Order.class);
		if(order==null) return Optional.empty();
		
		var check  =fieldInfo.stream().map(FieldInfo::name).collect(Collectors.toSet());
		var ordered=List.of(order.value());
		
		for(String name : ordered){
			if(check.remove(name)) continue;
			throw new MalformedTemplateStruct(
				name+" does not exist in "+interf.getName()+".\n"+
				"Existing field names: "+fieldInfo.stream().map(FieldInfo::name).toList()
			);
		}
		
		if(!check.isEmpty()){
			throw new MalformedTemplateStruct(check+" are not listed in the order annotation");
		}
		
		return Optional.of(ordered);
	}
	
	private static void defineStatics(JorthWriter writer) throws MalformedJorthException{
		writer.write(
			"""
				private visibility
				static final
				[#TOKEN(0)] typ.Struct $STRUCT field
				
				<clinit> function start
					typ.impl class
					typ.Struct of (1) static call
					typ.impl $STRUCT set
				end
				""");
	}
	
	private static void implementUserAccess(JorthWriter writer, FieldInfo info) throws MalformedJorthException{
		var jtyp=JorthUtils.toJorthGeneric(Objects.requireNonNull(TypeLink.of(info.type)));
		if(info.getter.isPresent()){
			var stub  =info.getter.get();
			var method=stub.method();
			
			writer.write(
				"""
					public visibility
					typ.Override @
					#RAW(1) returns
					#TOKEN(0) function start
						this #TOKEN(2) get
					end
					""",
				method.getName(),
				jtyp,
				info.name
			);
			
		}
		if(info.setter.isPresent()){
			var stub  =info.setter.get();
			var method=stub.method();
			
			writer.write(
				"""
					public visibility
					typ.Override @
					#RAW(1) arg1 arg
					#TOKEN(0) function start
						<arg> arg1 get
					""",
				method.getName(),
				jtyp
			);
			
			if(info.type==ChunkPointer.class){
				nullCheck(writer);
			}
			
			writer.write(
				"""
						this #TOKEN(0) set
					end
					""",
				info.name
			);
		}
	}
	
	private static void defineNoField(JorthWriter writer, FieldInfo info) throws MalformedJorthException{
		var jtyp=JorthUtils.toJorthGeneric(Objects.requireNonNull(TypeLink.of(info.type)));
		
		var getterName=info.getter.map(v->v.method().getName()).orElseGet(()->"get"+TextUtil.firstToUpperCase(info.name));
		var setterName=info.setter.map(v->v.method().getName()).orElseGet(()->"set"+TextUtil.firstToUpperCase(info.name));
		
		var anns=new ArrayList<>(info.annotations);
		anns.removeIf(a->(a instanceof IOValue v&&v.name().isEmpty())||a instanceof Override);
		
		var valAnn=anns.stream().filter(a->a instanceof IOValue).findAny().orElseGet(()->IOFieldTools.makeAnnotation(IOValue.class, Map.of("name", info.name)));
		
		if(anns.stream().noneMatch(a->a instanceof IOValue)) anns.add(valAnn);
		
		writeAnnotations(writer, anns);
		writer.write(
			"""
				public visibility
				#RAW(0) returns
				#TOKEN(1) function start
					typ.UnsupportedOperationException (0) new throw
				end
				""",
			jtyp,
			getterName
		);
		
		writeAnnotations(writer, List.of(valAnn));
		writer.write(
			"""
				public visibility
				#RAW(0) arg1 arg
				#TOKEN(1) function start
					typ.UnsupportedOperationException (0) new throw
				end
				""",
			jtyp,
			setterName
		);
	}
	
	private static void defineField(JorthWriter writer, FieldInfo info) throws MalformedJorthException{
		var type=Objects.requireNonNull(TypeLink.of(info.type));
		
		writer.write("private visibility");
		
		writeAnnotations(writer, info.annotations);
		
		writer.write(
			"#RAW(0) #TOKEN(1) field",
			JorthUtils.toJorthGeneric(type),
			info.name
		);
	}
	
	private static void writeAnnotations(JorthWriter writer, List<Annotation> annotations) throws MalformedJorthException{
		Set<Class<?>> annTypes=new HashSet<>();
		for(var ann : annotations){
			if(!annTypes.add(ann.annotationType())) continue;
			
			writer.write("{");
			scanAnnotation(ann, (name, value)->writer.write("#RAW(0) #TOKEN(1)", switch(value){
				case null -> "null";
				case String s -> "'"+s.replace("'", "\\'")+"'";
				case Enum<?> e -> e.name();
				case Boolean v -> v.toString();
				case Class<?> c -> c.getName();
				case Number n -> {
					if(SupportedPrimitive.isAny(n.getClass())) yield n+"";
					throw new UnsupportedOperationException();
				}
				case String[] strs -> Arrays.stream(strs).collect(Collectors.joining(" ", "[", "]"));
				default -> throw new NotImplementedException(value.getClass()+"");
			}, name));
			writer.write("} #TOKEN(0) @", ann.annotationType().getName());
		}
	}
	
	private static void nullCheck(JorthWriter writer) throws MalformedJorthException{
		writer.write(
			"""
				dup
				typ.Objects requireNonNull (1) static call
				pop
				""");
	}
	
	private static void scanAnnotation(Annotation ann, UnsafeBiConsumer<String, Object, MalformedJorthException> entry) throws MalformedJorthException{
		
		var c=ann.getClass();
		for(Method m : ann.annotationType().getMethods()){
			if(m.getParameterCount()!=0) continue;
			if(isStatic(m.getModifiers())) continue;
			
			if(m.getName().equals("annotationType")) continue;
			
			try{
				c.getSuperclass().getMethod(m.getName());
				continue;
			}catch(NoSuchMethodException ignored){}
			Object val;
			try{
				m.setAccessible(true);
				val=m.invoke(ann);
			}catch(Throwable e){
				throw new RuntimeException(e);
			}
			entry.accept(m.getName(), val);
		}
	}
	
	private static <T extends IOInstance<T>> Specials collectMethods(Class<T> interf, List<FieldStub> getters, List<FieldStub> setters){
		var set       =Optional.<Method>empty();
		var toStr     =Optional.<Method>empty();
		var toShortStr=Optional.<Method>empty();
		
		var q=new LinkedList<Class<?>>();
		q.add(interf);
		var last=new LinkedList<Class<?>>();
		while(!q.isEmpty()){
			var clazz=q.remove(0);
			last.add(clazz);
			
			toStr=toStr.or(()->Arrays.stream(clazz.getMethods()).filter(m->isToString(clazz, m, "toString")).findAny());
			toShortStr=toShortStr.or(()->Arrays.stream(clazz.getMethods()).filter(m->isToString(clazz, m, "toShortString")).findAny());
			if(toStr.isPresent()&&toShortStr.isPresent()) break;
			
			if(q.isEmpty()){
				last.stream()
				    .flatMap(t->Arrays.stream(t.getInterfaces()))
				    .filter(i->i!=IOInstance.Def.class&&UtilL.instanceOf(interf, IOInstance.Def.class))
				    .forEach(q::add);
				last.clear();
			}
		}
		
		for(Method method : interf.getMethods()){
			if(Modifier.isStatic(method.getModifiers())||!Modifier.isAbstract(method.getModifiers())){
				continue;
			}
			if(IGNORE_TYPES.contains(method.getDeclaringClass())){
				continue;
			}
			
			if(List.of("set", "setAll").contains(method.getName())){
				if(method.getReturnType()!=void.class) throw new MalformedTemplateStruct("set can not have a return type");
				if(set.isPresent()) throw new MalformedTemplateStruct("duplicate set method");
				set=Optional.of(method);
				continue;
			}
			
			var getter=CompilationTools.asGetterStub(method);
			var setter=CompilationTools.asSetterStub(method);
			
			getter.ifPresent(getters::add);
			setter.ifPresent(setters::add);
			
			if(getter.or(()->setter).isEmpty()){
				throw new MalformedTemplateStruct(method+" is not a setter or a getter!");
			}
		}
		
		return new Specials(set, toStr, toShortStr);
	}
	
	private static boolean isToString(Class<?> interf, Method method, String name){
		if(!Modifier.isStatic(method.getModifiers())) return false;
		if(!method.getName().equals(name)) return false;
		if(method.getReturnType()!=String.class) return false;
		if(method.getParameterCount()!=1) return false;
		var type=method.getParameterTypes()[0];
		return UtilL.instanceOf(type, interf);
	}
	
	private static List<FieldInfo> mergeStubs(List<FieldStub> getters, List<FieldStub> setters){
		return Stream.concat(getters.stream(), setters.stream())
		             .map(FieldStub::varName)
		             .distinct()
		             .map(name->{
			             var getter=getters.stream().filter(s->s.varName().equals(name)).findAny();
			             var setter=setters.stream().filter(s->s.varName().equals(name)).findAny();
			
			             var gors=getter.or(()->setter).orElseThrow();
			
			             var type=gors.type();
			
			             var anns=Stream.concat(getter.stream(), setter.stream())
			                            .map(f->f.method().getAnnotations())
			                            .flatMap(Arrays::stream)
			                            .filter(a->FieldCompiler.ANNOTATION_TYPES.contains(a.annotationType()))
			                            .collect(Collectors.toList());
			
			             IOValue valBack=null;
			             var     iter   =anns.iterator();
			             while(iter.hasNext()){
				             var ann=iter.next();
				             if(ann instanceof IOValue val){
					             if(val.name().equals("")){
						             valBack=val;
						             iter.remove();
						             continue;
					             }
					             throw new MalformedTemplateStruct(gors.varName()+": @IOValue can not contain a name");
				             }
			             }
			             if(valBack==null) valBack=IOFieldTools.makeAnnotation(IOValue.class);
			             anns.add(valBack);
			
			             return new FieldInfo(name, type, anns, getter, setter);
		             })
		             .toList();
	}
	
	private static void checkStyles(List<FieldStub> getters, List<FieldStub> setters){
		var styles=Stream.concat(getters.stream(), setters.stream()).collect(Collectors.groupingBy(FieldStub::style));
		
		if(styles.size()>1){
			var style=styles.entrySet().stream().reduce((a, b)->a.getValue().size()>b.getValue().size()?a:b).map(Map.Entry::getKey).orElseThrow();
			throw new MalformedTemplateStruct(
				"Inconsistent getter/setter styles!\n"+
				"Style patterns:\n"+styles.keySet().stream().map(s->"\t"+s+":\t"+s.humanPattern).collect(Collectors.joining("\n"))+"\n"+
				"Most common style is:\n"+style+"\n"+
				"Bad styles:\n"+
				styles.entrySet()
				      .stream()
				      .filter(e->e.getKey()!=style)
				      .map(Map.Entry::getValue)
				      .flatMap(Collection::stream)
				      .map(s->"\t"+s.method().getName()+":\t"+s.style())
				      .collect(Collectors.joining("\n"))
			);
		}
	}
	
	private static void checkAnnotations(List<FieldInfo> fields){
		var problems=fields.stream()
		                   .map(gs->{
			                        var dup=gs.stubs()
			                                  .flatMap(g->Arrays.stream(g.method().getAnnotations()))
			                                  .filter(a->FieldCompiler.ANNOTATION_TYPES.contains(a.annotationType()))
			                                  .collect(Collectors.groupingBy(Annotation::annotationType))
			                                  .values().stream()
			                                  .filter(l->l.size()>1)
			                                  .map(l->"\t\t"+l.get(0).annotationType().getName())
			                                  .collect(Collectors.joining("\n"));
			                        if(dup.isEmpty()) return "";
			                        return "\t"+gs.name+":\n"+dup;
		                        }
		                   ).filter(s->!s.isEmpty())
		                   .collect(Collectors.joining("\n"));
		
		if(!problems.isEmpty()){
			throw new MalformedTemplateStruct("Duplicate annotations:\n"+problems);
		}
	}
	
	private static void checkTypes(List<FieldInfo> fields){
		var problems=fields.stream()
		                   .filter(gs->gs.stubs().collect(Collectors.groupingBy(FieldStub::type)).size()>1)
		                   .map(gs->"\t"+gs.name+":\n"+
		                            "\t\t"+gs.stubs().map(g->(g.style()!=Style.RAW?g.method().getName()+":\t":"")+g.type()+"")
		                                     .collect(Collectors.joining("\n\t\t"))
		                   )
		                   .collect(Collectors.joining("\n"));
		
		if(!problems.isEmpty()){
			throw new MalformedTemplateStruct("Mismatched types:\n"+problems);
		}
	}
	
	private static void checkModel(List<FieldInfo> fieldInfo){
		var reg=FieldCompiler.registry();
		for(var field : fieldInfo){
			var ann=GetAnnotation.from(field.annotations);
			reg.requireCanCreate(FieldCompiler.getType(field.type, ann), ann);
		}
	}
}
