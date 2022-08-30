package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.internal.Access;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.type.*;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.SupportedPrimitive.BOOLEAN;
import static java.lang.reflect.Modifier.isStatic;

public class DefInstanceCompiler{
	
	private static final boolean PRINT_BYTECODE=GlobalConfig.configFlag("classGen.printBytecode", false);
	private static final boolean EXIT_ON_FAIL  =GlobalConfig.configFlag("classGen.exitOnFail", false);
	
	private enum Style{
		NAMED("FieldType getName() / void setName(FieldType newValue)"),
		RAW("FieldType name() / void name(FieldType newValue)");
		
		final String humanPattern;
		Style(String humanPattern){this.humanPattern=humanPattern;}
	}
	
	private record Specials(Method set){}
	
	private record FieldStub(Method method, String varName, Type type, Style style){}
	
	private record FieldInfo(String name, Type type, List<Annotation> annotations, Optional<FieldStub> getter, Optional<FieldStub> setter){
		Stream<FieldStub> stubs(){return Stream.concat(getter.stream(), setter.stream());}
	}
	
	private static final class ImplNode<T extends IOInstance<T>>{
		enum State{
			NEW, COMPILING, DONE
		}
		
		private final Lock lock=new ReentrantLock();
		
		private State          state=State.NEW;
		private Class<T>       interf;
		private Class<T>       impl;
		private Constructor<T> dataConstructor;
		
		public ImplNode(Class<T> interf){
			this.interf=interf;
		}
		
		private void init(Class<T> impl, Optional<List<FieldInfo>> oOrderedFields){
			this.impl=impl;
			if(oOrderedFields.isPresent()){
				var ordered=oOrderedFields.get();
				
				try{
					dataConstructor=impl.getConstructor(ordered.stream().map(f->Utils.typeToRaw(f.type)).toArray(Class[]::new));
				}catch(NoSuchMethodException e){
					throw new ShouldNeverHappenError(e);
				}
			}
		}
	}
	
	private static Optional<String> namePrefix(Method m, String prefix){
		var name=m.getName();
		if(name.length()<=prefix.length()) return Optional.empty();
		if(!name.startsWith(prefix)) return Optional.empty();
		if(Character.isLowerCase(name.charAt(prefix.length()))) return Optional.empty();
		return Optional.of(TextUtil.firstToLowerCase(name.substring(prefix.length())));
	}
	
	private static final List<Function<Method, Optional<FieldStub>>> GETTER_PATTERNS=List.of(
		m->{// FieldType getName()
			if(m.getParameterCount()!=0) return Optional.empty();
			var type=m.getGenericReturnType();
			if(type==void.class) return Optional.empty();
			
			return namePrefix(m, "get").map(name->new FieldStub(m, name, type, Style.NAMED));
		},
		m->{// (b/B)oolean isName()
			if(m.getParameterCount()!=0) return Optional.empty();
			var type=m.getGenericReturnType();
			if(SupportedPrimitive.get(type).orElse(null)!=BOOLEAN) return Optional.empty();
			
			return namePrefix(m, "is").map(name->new FieldStub(m, name, type, Style.NAMED));
		},
		m->{// FieldType name()
			if(m.getParameterCount()!=0) return Optional.empty();
			var name=m.getName();
			var type=m.getGenericReturnType();
			return Optional.of(new FieldStub(m, name, type, Style.RAW));
		}
	);
	private static final List<Function<Method, Optional<FieldStub>>> SETTER_PATTERNS=List.of(
		m->{// void setName(FieldType newValue)
			if(m.getParameterCount()!=1) return Optional.empty();
			if(m.getReturnType()!=void.class) return Optional.empty();
			
			return namePrefix(m, "set").map(name->new FieldStub(m, name, m.getGenericParameterTypes()[0], Style.NAMED));
		},
		m->{// void name(FieldType newValue)
			if(m.getParameterCount()!=1) return Optional.empty();
			if(m.getReturnType()!=void.class) return Optional.empty();
			var name=m.getName();
			var type=m.getGenericParameterTypes()[0];
			return Optional.of(new FieldStub(m, name, type, Style.RAW));
		}
	);
	
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
	
	private static final ConcurrentHashMap<Class<?>, ImplNode<?>> CACHE=new ConcurrentHashMap<>();
	
	public static boolean isTemplate(Class<?> clazz){
		return clazz.isInterface()&&UtilL.instanceOf(clazz, IOInstance.Def.class);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance.Def<T>> Class<T> unmap(Class<?> impl){
		Objects.requireNonNull(impl);
		if(!UtilL.instanceOf(impl, IOInstance.Def.class)) throw new IllegalArgumentException(impl.getName()+"");
		var clazz=impl;
		while(true){
			for(Class<?> interf : clazz.getInterfaces()){
				var node=CACHE.get(interf);
				if(node.impl==impl){
					return (Class<T>)node.interf;
				}
			}
			clazz=clazz.getSuperclass();
			if(clazz==null) throw new ShouldNeverHappenError("???");
		}
	}
	
	public static <T extends IOInstance<T>> Constructor<T> dataConstructor(Class<T> interf){
		var ctr=getNode(interf).dataConstructor;
		if(ctr==null) throw new RuntimeException("Please add "+IOInstance.Def.Order.class.getName()+" to "+interf.getName());
		return ctr;
	}
	
	public static <T extends IOInstance<T>> Class<T> getImpl(Class<T> interf){
		return getNode(interf).impl;
	}
	
	private static <T extends IOInstance<T>> ImplNode<T> getNode(Class<T> interf){
		if(!isTemplate(interf)){
			throw new IllegalArgumentException(interf+"");
		}
		
		@SuppressWarnings("unchecked")
		var node=(ImplNode<T>)CACHE.computeIfAbsent(interf, i->new ImplNode<>((Class<T>)i));
		
		node.lock.lock();
		try{
			switch(node.state){
				case null -> throw new ShouldNeverHappenError();
				case NEW -> {}
				case COMPILING -> throw new MalformedStructLayout("Type requires itself to compile");
				case DONE -> {return node;}
			}
			
			Log.trace("Generating implementation of: {}", interf.getName());
			
			node.state=ImplNode.State.COMPILING;
			
			var getters =new ArrayList<FieldStub>();
			var setters =new ArrayList<FieldStub>();
			var specials=collectMethods(interf, getters, setters);
			
			checkStyles(getters, setters);
			
			var fieldInfo=mergeStubs(getters, setters);
			
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
				impl=generateImpl(interf, specials, fieldInfo, orderedFields);
			}catch(Throwable e){
				if(EXIT_ON_FAIL){
					e.printStackTrace();
					System.exit(1);
				}
				throw e;
			}
			
			node.init(impl, orderedFields);
			
			node.state=ImplNode.State.DONE;
			
			return node;
		}catch(Throwable e){
			node.state=ImplNode.State.NEW;
			throw e;
		}finally{
			node.lock.unlock();
		}
	}
	
	private static <T extends IOInstance<T>> void checkClass(Class<T> interf, Specials specials, List<FieldInfo> fields, Optional<List<FieldInfo>> oOrderedFields){
		
		if(specials.set!=null){
			if(oOrderedFields.isEmpty()){
				throw new MalformedStructLayout(interf.getName()+" has a full setter but no argument order. Please add "+IOInstance.Def.Order.class.getName()+" to the type");
			}
			
			var orderedFields=oOrderedFields.get();
			
			var set=specials.set;
			if(set.getParameterCount()!=orderedFields.size()){
				throw new MalformedStructLayout(set+" has "+set.getParameterCount()+" parameters but has "+orderedFields.size()+" fields");
			}
			
			var parms=set.getGenericParameterTypes();
			for(int i=0;i<orderedFields.size();i++){
				var field   =orderedFields.get(i);
				var parmType=parms[i];
				
				if(field.type.equals(parmType)) continue;
				
				//TODO: implement fits type instead of exact match
				throw new MalformedStructLayout(field.name+" has the type of "+field.type.getTypeName()+" but set argument is "+parmType);
			}
		}
	}
	
	private static <T extends IOInstance<T>> Class<T> generateImpl(Class<T> interf, Specials specials, List<FieldInfo> fieldInfo, Optional<List<FieldInfo>> orderedFields){
		var implName=interf.getName()+IOInstance.Def.IMPL_NAME_POSTFIX;
		
		try{
			JorthCompiler jorth=new JorthCompiler(interf.getClassLoader());
			
			try(var writer=jorth.writeCode()){
				writer.write("#TOKEN(0) typ.impl               define", implName);
				writer.write("#TOKEN(0) typ.interf             define", interf.getName());
				writer.write("#TOKEN(0) typ.IOInstance.Managed define", IOInstance.Managed.class.getName());
				writer.write("#TOKEN(0) typ.Override           define", Override.class.getName());
				writer.write("#TOKEN(0) typ.String             define", String.class.getName());
				writer.write("#TOKEN(0) typ.StringBuilder      define", StringBuilder.class.getName());
				writer.write("#TOKEN(0) typ.Objects            define", Objects.class.getName());
				writer.write("#TOKEN(0) typ.Struct             define", Struct.class.getName());
				writer.write("#TOKEN(0) typ.ChunkPointer       define", ChunkPointer.class.getName());
				
				writer.write(
					"""
						public visibility
						typ.interf implements
						[typ.impl] typ.IOInstance.Managed extends
						typ.impl class start
						""");
				
				for(FieldInfo info : fieldInfo){
					defineField(writer, info);
					implementUserAccess(writer, info);
				}
				
				defineStatics(writer);
				
				
				generateConstructors(fieldInfo, orderedFields, writer);
				
				if(specials.set!=null){
					var ordered=orderedFields.orElseThrow();
					
					var set=specials.set;
					
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
				
				stringSaga:
				{
					var format=interf.getAnnotation(IOInstance.Def.ToString.Format.class);
					if(format!=null){
						generateFormatToString(interf, fieldInfo, writer, format);
						break stringSaga;
					}
					
					var toStrAnn=interf.getAnnotation(IOInstance.Def.ToString.class);
					if(toStrAnn!=null){
						generateStandardToString(interf, orderedFields.orElse(fieldInfo), writer, toStrAnn);
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
	
	private static <T extends IOInstance<T>> void generateFormatToString(Class<T> interf, List<FieldInfo> fieldInfo, JorthWriter writer, IOInstance.Def.ToString.Format format) throws MalformedJorthException{
		var fragment=ToStringFormat.parse(format.value(), fieldInfo.stream().map(FieldInfo::name).toList());
		
		generateFormatToString(interf, fieldInfo, "toString", true, fragment, writer);
		generateFormatToString(interf, fieldInfo, "toShortString", false, fragment, writer);
	}
	
	private static <T extends IOInstance<T>> void generateStandardToString(Class<T> interf, List<FieldInfo> fieldInfo, JorthWriter writer, IOInstance.Def.ToString toStrAnn) throws MalformedJorthException{
		generateStandardToString(interf, toStrAnn, "toString", fieldInfo, writer);
		if(toStrAnn.name()){
			generateStandardToString(interf, IOFieldTools.makeAnnotation(IOInstance.Def.ToString.class, Map.of(
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
	
	private static void generateFormatToString(Class<?> interf, List<FieldInfo> fieldInfo, String name, boolean all, ToStringFormat.ToStringFragment fragment, JorthWriter writer) throws MalformedJorthException{
		
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
		processFragments(interf, fragment, all, frag->{
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
		
		executeStringFragment(interf, fieldInfo, new Concat(compact), all, writer);
		
		writer.write(
			"""
					toString (0) call
				end
				""");
	}
	
	private static void processFragments(Class<?> interf, ToStringFormat.ToStringFragment fragment, boolean all, Consumer<ToStringFormat.ToStringFragment> out) throws MalformedJorthException{
		switch(fragment){
			case NOOP f -> {}
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
	
	private static void generateStandardToString(Class<?> interf, IOInstance.Def.ToString toStrAnn, String name, List<FieldInfo> fieldInfo, JorthWriter writer) throws MalformedJorthException{
		
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
			append(writer, w->w.write("'#RAW(0)'", interf.getSimpleName()));
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
			throw new MalformedStructLayout(
				name+" does not exist in "+interf.getName()+".\n"+
				"Existing field names: "+fieldInfo.stream().map(FieldInfo::name).toList()
			);
		}
		
		if(!check.isEmpty()){
			throw new MalformedStructLayout(check+" are not listed in the order annotation");
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
			var method=stub.method;
			
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
			var method=stub.method;
			
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
	
	private static void defineField(JorthWriter writer, FieldInfo info) throws MalformedJorthException{
		var type=Objects.requireNonNull(TypeLink.of(info.type));
		
		writer.write("private visibility");
		
		Set<Class<?>> annTypes=new HashSet<>();
		for(var ann : info.annotations){
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
		
		writer.write(
			"#RAW(0) #TOKEN(1) field",
			JorthUtils.toJorthGeneric(type),
			info.name
		);
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
		Method set=null;
		for(Method method : interf.getMethods()){
			if(Modifier.isStatic(method.getModifiers())||!Modifier.isAbstract(method.getModifiers())){
				continue;
			}
			if(IGNORE_TYPES.contains(method.getDeclaringClass())){
				continue;
			}
			
			if(List.of("set", "setAll").contains(method.getName())){
				if(method.getReturnType()!=void.class) throw new MalformedStructLayout("set can not have a return type");
				if(set!=null) throw new MalformedStructLayout("duplicate set method");
				set=method;
				continue;
			}
			
			var getter=GETTER_PATTERNS.stream().map(f->f.apply(method)).filter(Optional::isPresent).map(Optional::get).findFirst();
			var setter=SETTER_PATTERNS.stream().map(f->f.apply(method)).filter(Optional::isPresent).map(Optional::get).findFirst();
			
			getter.ifPresent(getters::add);
			setter.ifPresent(setters::add);
			
			if(getter.or(()->setter).isEmpty()){
				throw new MalformedStructLayout(method+" is not a setter or a getter!");
			}
		}
		return new Specials(set);
	}
	
	private static List<FieldInfo> mergeStubs(List<FieldStub> getters, List<FieldStub> setters){
		return Stream.concat(getters.stream(), setters.stream())
		             .map(FieldStub::varName)
		             .distinct()
		             .map(name->{
			             var getter=getters.stream().filter(s->s.varName.equals(name)).findAny();
			             var setter=setters.stream().filter(s->s.varName.equals(name)).findAny();
			
			             var gors=getter.or(()->setter).orElseThrow();
			
			             var type=gors.type;
			
			             var anns=Stream.concat(getter.stream(), setter.stream())
			                            .map(f->f.method.getAnnotations())
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
					             throw new MalformedStructLayout(gors.varName+": @IOValue can not contain a name");
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
			throw new MalformedStructLayout(
				"Inconsistent getter/setter styles!\n"+
				"Style patterns:\n"+styles.keySet().stream().map(s->"\t"+s+":\t"+s.humanPattern).collect(Collectors.joining("\n"))+"\n"+
				"Most common style is:\n"+style+"\n"+
				"Bad styles:\n"+
				styles.entrySet()
				      .stream()
				      .filter(e->e.getKey()!=style)
				      .map(Map.Entry::getValue)
				      .flatMap(Collection::stream)
				      .map(s->"\t"+s.method.getName()+":\t"+s.style)
				      .collect(Collectors.joining("\n"))
			);
		}
	}
	
	private static void checkAnnotations(List<FieldInfo> fields){
		var problems=fields.stream()
		                   .map(gs->{
			                        var dup=gs.stubs()
			                                  .flatMap(g->Arrays.stream(g.method.getAnnotations()))
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
			throw new MalformedStructLayout("Duplicate annotations:\n"+problems);
		}
	}
	
	private static void checkTypes(List<FieldInfo> fields){
		var problems=fields.stream()
		                   .filter(gs->gs.stubs().collect(Collectors.groupingBy(s->s.type)).size()>1)
		                   .map(gs->"\t"+gs.name+":\n"+
		                            "\t\t"+gs.stubs().map(g->(g.style!=Style.RAW?g.method.getName()+":\t":"")+g.type+"")
		                                     .collect(Collectors.joining("\n\t\t"))
		                   )
		                   .collect(Collectors.joining("\n"));
		
		if(!problems.isEmpty()){
			throw new MalformedStructLayout("Mismatched types:\n"+problems);
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
