package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.type.*;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.*;
import com.lapissea.cfs.type.field.annotations.*;
import com.lapissea.cfs.type.field.fields.reflection.*;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.PairM;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import ru.vyarus.java.generics.resolver.GenericsResolver;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.function.Function.identity;

@SuppressWarnings("rawtypes")
public class FieldCompiler{
	
	protected record LogicalAnnotation<T extends Annotation>(T annotation, AnnotationLogic<T> logic){}
	
	public static FieldCompiler create(){
		return new FieldCompiler();
	}
	
	private record AnnotatedField<T extends IOInstance<T>>(
		IOField<T, ?> field,
		List<LogicalAnnotation<Annotation>> annotations
	) implements Comparable<AnnotatedField<T>>{
		@Override
		public int compareTo(AnnotatedField<T> o){
			return field().getAccessor().compareTo(o.field.getAccessor());
		}
	}
	
	protected FieldCompiler(){
	}
	
	public <T extends IOInstance.Unmanaged<T>> FieldSet<T> compileStaticUnmanaged(Struct.Unmanaged<T> struct){//TODO: implement static unmanaged fields
		var type=struct.getType();
		
		var methods=type.getDeclaredMethods();
		
		var valueDefs=Arrays.stream(methods).filter(m->m.isAnnotationPresent(IOValueUnmanaged.class)).toList();
		if(valueDefs.isEmpty()) return FieldSet.of();
		
		for(Method valueMethod : valueDefs){
			if(!Modifier.isStatic(valueMethod.getModifiers())){
				throw new MalformedStructLayout(valueMethod+" is not static!");
			}
			
			var context=GenericsResolver.resolve(valueMethod.getDeclaringClass()).method(valueMethod);
			
			if(!UtilL.instanceOf(context.resolveReturnClass(), IOField.class)){
				throw new MalformedStructLayout(valueMethod+" does not return "+IOField.class.getName());
			}
			
			Class<?> ioFieldOwner=context.returnType().type(IOField.class).generic("T");
			
			if(ioFieldOwner!=valueMethod.getDeclaringClass()){
				throw new MalformedStructLayout(valueMethod+" does not return IOField of same owner type!\n"+ioFieldOwner.getName()+"\n"+valueMethod.getDeclaringClass().getName());
			}
		}
		
		return FieldSet.of(valueDefs.stream().map(valueDef->{
			valueDef.setAccessible(true);
			
			try{
				//noinspection unchecked
				return (IOField<T, ?>)valueDef.invoke(null);
			}catch(ReflectiveOperationException e){
				throw new RuntimeException(e);
			}
		}));
	}
	
	public <T extends IOInstance<T>> FieldSet<T> compile(Struct<T> struct){
		var fields=scanFields(struct)
			           .map(f->registry().create(f, null))
			           .map(f->new AnnotatedField<>(f, scanAnnotations(f)))
			           .collect(Collectors.toList());
		
		generateVirtualFields(fields, struct);
		
		validate(fields);
		
		for(var pair : fields){
			var depAn=pair.annotations;
			var field=pair.field;
			
			field.initLateData(FieldSet.of(generateDependencies(fields, depAn, field)),
			                   fields.stream()
			                         .flatMap(f->f.annotations.stream()
			                                                  .flatMap(an->an.logic.getHints(f.field.getAccessor(), an.annotation)
			                                                                       .map(h->h.target()==null?
			                                                                               new IOField.UsageHintDefinition(h.type(), f.field.getName()):
			                                                                               h)
			                                                  )
			                         )
			                         .filter(t->t.target().equals(field.getName()))
			                         .map(IOField.UsageHintDefinition::type)
			);
		}
		
		return FieldSet.of(fields.stream().map(AnnotatedField::field));
	}
	
	private <T extends IOInstance<T>> Collection<IOField<T, ?>> generateDependencies(List<AnnotatedField<T>> fields, List<LogicalAnnotation<Annotation>> depAn, IOField<T, ?> field){
		Collection<IOField<T, ?>> dependencies=new HashSet<>();
		
		for(var ann : depAn){
			ann.logic().validate(field.getAccessor(), ann.annotation());
			
			var depNames=ann.logic().getDependencyValueNames(field.getAccessor(), ann.annotation());
			if(depNames.size()==0) continue;
			
			var missingNames=depNames.stream()
			                         .filter(name->fields.stream().noneMatch(f->f.field.getName().equals(name)))
			                         .collect(Collectors.joining(", "));
			if(!missingNames.isEmpty()) throw new MalformedStructLayout("Could not find dependencies "+missingNames+" on field "+field.getAccessor());
			
			for(String nam : depNames){
				IOField<T, ?> e=fields.stream().filter(f->f.field.getName().equals(nam)).findAny().orElseThrow().field;
				if(!dependencies.add(e)) throw new MalformedStructLayout("Duplicate dependency "+e.getAccessor());
			}
		}
		return dependencies;
	}
	
	private <T extends IOInstance<T>> void validate(List<AnnotatedField<T>> parsed){
		for(var pair : parsed){
			var nam=pair.field.getName();
			var err=IntStream.of('.', '/', '\\', ' ')
			                 .filter(c->nam.indexOf((char)c)!=-1)
			                 .mapToObj(c->"Character "+((char)c)+" is not allowed in field name \""+nam+"\"! ")
			                 .findAny();
			if(err.isPresent()) throw new MalformedStructLayout(err.get());
			
			var field=pair.field.getAccessor();
			pair.annotations.forEach(ann->ann.logic().validate(field, ann.annotation()));
		}
	}
	
	private <T extends IOInstance<T>> void generateVirtualFields(List<AnnotatedField<T>> parsed, Struct<T> struct){
		
		Map<VirtualFieldDefinition.StoragePool, Integer> accessIndex    =new EnumMap<>(VirtualFieldDefinition.StoragePool.class);
		Map<VirtualFieldDefinition.StoragePool, Integer> primitiveOffset=new EnumMap<>(VirtualFieldDefinition.StoragePool.class);
		Map<String, FieldAccessor<T>>                    virtualData    =new HashMap<>();
		Map<String, FieldAccessor<T>>                    newVirtualData =new HashMap<>();
		
		List<AnnotatedField<T>> toRun=new ArrayList<>(parsed);
		
		do{
			for(var pair : toRun){
				for(var logicalAnn : pair.annotations){
					for(var s : logicalAnn.logic().injectPerInstanceValue(pair.field.getAccessor(), logicalAnn.annotation())){
						var existing=virtualData.get(s.getName());
						if(existing!=null){
							var gTyp=existing.getGenericType(null);
							if(!gTyp.equals(s.getType())){
								throw new MalformedStructLayout("Virtual field "+existing.getName()+" already defined but has a type conflict of "+gTyp+" and "+s.getType());
							}
							continue;
						}
						
						int primitiveSize, off, ptrIndex;
						
						if(!(s.getType() instanceof Class<?> c)||!c.isPrimitive()){
							primitiveSize=off=-1;
							ptrIndex=accessIndex.compute(s.storagePool, (k, v)->v==null?0:v+1);
						}else{
							if(List.of(long.class, double.class).contains(s.getType())){
								primitiveSize=8;
							}else{
								primitiveSize=4;
							}
							off=primitiveOffset.getOrDefault(s.storagePool, 0);
							int offEnd=off+primitiveSize;
							primitiveOffset.put(s.storagePool, offEnd);
							
							ptrIndex=-1;
						}
						
						FieldAccessor<T> accessor=new VirtualAccessor<>(struct, (VirtualFieldDefinition<T, Object>)s, ptrIndex, off, primitiveSize);
						virtualData.put(s.getName(), accessor);
						newVirtualData.put(s.getName(), accessor);
					}
				}
			}
			toRun.clear();
			for(var virtual : newVirtualData.values()){
				var field    =registry().create(virtual, null);
				var annotated=new AnnotatedField<>(field, scanAnnotations(field));
				toRun.add(annotated);
				UtilL.addRemainSorted(parsed, annotated);
			}
			newVirtualData.clear();
		}while(!toRun.isEmpty());
	}
	
	
	protected IterablePP<Field> deepFieldsByAnnotation(Class<?> clazz, Class<? extends Annotation> type){
		return IterablePP
			       .nullTerminated(()->new Supplier<Class<?>>(){
				       Class<?> c=clazz;
				       @Override
				       public Class<?> get(){
					       if(c==null) return null;
					       var tmp=c;
					       var cp =c.getSuperclass();
					       c=cp==c?null:cp;
					
					       return tmp;
				       }
			       })
			       .flatMap(c->Arrays.asList(c.getDeclaredFields()).iterator())
			       .filtered(f->f.isAnnotationPresent(type));
	}
	
	protected <T extends IOInstance<T>> Stream<FieldAccessor<T>> scanFields(Struct<T> struct){
		var cl=struct.getType();
		
		List<FieldAccessor<T>> fields    =new ArrayList<>();
		List<Method>           usedFields=new ArrayList<>();
		
		for(Field field : deepFieldsByAnnotation(cl, IOValue.class)){
			try{
				Type type=getType(field);
				
				registry().requireCanCreate(type, field::getAnnotation);
				field.setAccessible(true);
				
				String fieldName=getFieldName(field);
				
				Function<String, Optional<Method>> getMethod=prefix->scanMethod(cl, m->checkMethod(fieldName, prefix, m));
				
				var getter=calcGetPrefixes(field).map(getMethod).filter(Optional::isPresent).map(Optional::get).findAny();
				var setter=getMethod.apply("set");
				
				getter.ifPresent(usedFields::add);
				setter.ifPresent(usedFields::add);
				
				var def=Runtime.version().feature()<=18?"unsafe":"varhandle";
				fields.add(switch(UtilL.sysPropertyByClass(FieldCompiler.class, "UNSAFE_ACCESS").orElse(def).toLowerCase()){
					case "unsafe" -> UnsafeAccessor.make(struct, field, getter, setter, fieldName, type);
					case "varhandle" -> VarHandleAccessor.make(struct, field, getter, setter, fieldName, type);
					default -> ReflectionAccessor.make(struct, field, getter, setter, fieldName, type);
				});
			}catch(Throwable e){
				throw new MalformedStructLayout("Failed to scan field #"+field.getName(), e);
			}
		}
		
		var hangingMethods=scanMethods(cl, method->method.isAnnotationPresent(IOValue.class)&&!usedFields.contains(method)).toList();
		
		Map<String, PairM<Method, Method>> transientFieldsMap=new HashMap<>();
		
		for(Method hangingMethod : hangingMethods){
			calcGetPrefixes(hangingMethod).map(p->getMethodFieldName(p, hangingMethod)).filter(Optional::isPresent).map(Optional::get)
			                              .findFirst().ifPresent(s->transientFieldsMap.computeIfAbsent(s, n->new PairM<>()).obj1=hangingMethod);
			getMethodFieldName("set", hangingMethod).ifPresent(s->transientFieldsMap.computeIfAbsent(s, n->new PairM<>()).obj2=hangingMethod);
		}
		
		record Err(String fieldName, boolean getter, boolean setter){}
		
		
		var errors=transientFieldsMap.entrySet()
		                             .stream()
		                             .filter(e->e.getValue().obj1==null||e.getValue().obj2==null)
		                             .map(e->new Err(e.getKey(), e.getValue().obj1!=null, e.getValue().obj2!=null))
		                             .toList();
		if(!errors.isEmpty()){
			throw new MalformedStructLayout("Invalid transient IO field for "+cl.getName()+":\n"+TextUtil.toTable(errors));
		}
		
		var unusedWaning=hangingMethods.stream()
		                               .filter(m->transientFieldsMap.values()
		                                                            .stream()
		                                                            .flatMap(PairM::<Method>stream)
		                                                            .noneMatch(mt->mt==m))
		                               .map(method->method+""+(fields.stream().anyMatch(f->f.getName().equals(method.getName()))?(
			                               " did you mean "+calcGetPrefixes(method).map(p->p+TextUtil.firstToUpperCase(method.getName())).collect(Collectors.joining(" or "))+"?"
		                               ):""))
		                               .collect(Collectors.joining("\n"));
		if(!unusedWaning.isEmpty()){
			throw new MalformedStructLayout("There are unused or invalid methods marked with "+IOValue.class.getSimpleName()+"\n"+unusedWaning);
		}
		
		return Stream.concat(
			fields.stream(),
			transientFieldsMap.entrySet().stream().<FieldAccessor<T>>map(e->{
				String name=e.getKey();
				var    p   =e.getValue();
				
				Method getter=p.obj1, setter=p.obj2;
				
				var annotations=GetAnnotation.from(Stream.of(getter.getAnnotations(), setter.getAnnotations())
				                                         .flatMap(Arrays::stream)
				                                         .distinct()
				                                         .collect(Collectors.toMap(Annotation::annotationType, identity())));
				Type type=getType(getter.getGenericReturnType(), annotations);
				
				Type setType=setter.getGenericParameterTypes()[0];
				if(!Utils.genericInstanceOf(type, setType)){
					throw new MalformedStructLayout(setType+" is not a valid argument in\n"+setter);
				}
				
				return FunctionalReflectionAccessor.make(struct, name, getter, setter, annotations, type);
			})
		).sorted();
	}
	
	private Stream<String> calcGetPrefixes(Field field)  {return calcGetPrefixes(field.getType());}
	private Stream<String> calcGetPrefixes(Method method){return calcGetPrefixes(method.getReturnType());}
	private Stream<String> calcGetPrefixes(Class<?> typ){
		var isBool=typ==boolean.class||typ==Boolean.class;
		if(isBool) return Stream.of("is", "get");
		return Stream.of("get");
	}
	
	private Optional<String> getMethodFieldName(String prefix, Method m){
		IOValue ann  =m.getAnnotation(IOValue.class);
		var     mName=m.getName();
		if(!mName.startsWith(prefix)) return Optional.empty();
		
		if(ann.name().isEmpty()){
			var n=mName.substring(prefix.length());
			if(n.isEmpty()||Character.isLowerCase(n.charAt(0))) return Optional.empty();
			return Optional.of(TextUtil.firstToLowerCase(mName.substring(prefix.length())));
		}else{
			return Optional.of(ann.name());
		}
	}
	private boolean checkMethod(String fieldName, String prefix, Method m){
		if(Modifier.isStatic(m.getModifiers())) return false;
		if(!m.isAnnotationPresent(IOValue.class)) return false;
		var name=getMethodFieldName(prefix, m);
		return name.isPresent()&&name.get().equals(fieldName);
	}
	private String getFieldName(Field field){
		String fieldName;
		{
			var ann=field.getAnnotation(IOValue.class);
			fieldName=ann.name().isEmpty()?field.getName():ann.name();
		}
		return fieldName;
	}
	
	private Type getType(Field field){
		return getType(field.getGenericType(), field::getAnnotation);
	}
	private Type getType(Type defaultType, GetAnnotation getAnnotation){
		Type type        =defaultType;
		var  typeOverride=getAnnotation.get(IOValue.OverrideType.class);
		if(typeOverride!=null){
			var      rawType=SyntheticParameterizedType.generalize(type);
			Class<?> raw    =rawType.getRawType();
			Type[]   parms  =rawType.getActualTypeArguments();
			
			if(typeOverride.value()!=Object.class) raw=typeOverride.value();
			if(typeOverride.genericArgs().length!=0) parms=typeOverride.genericArgs();
			
			type=new SyntheticParameterizedType(raw, parms);
		}
		return type;
	}
	
	private Stream<Method> scanMethods(Class<?> clazz, Predicate<Method> check){
		return Stream.iterate(clazz, Objects::nonNull, (UnaryOperator<Class<?>>)Class::getSuperclass)
		             .flatMap(c->Arrays.stream(c.getDeclaredMethods()))
		             .filter(check);
	}
	private Optional<Method> scanMethod(Class<?> clazz, Predicate<Method> check){
		return scanMethods(clazz, check).findAny();
	}
	
	protected <T extends IOInstance<T>> List<LogicalAnnotation<Annotation>> scanAnnotations(IOField<T, ?> field){
		return activeAnnotations()
			       .stream()
			       .flatMap(ann->Stream.concat(Stream.of(ann), Arrays.stream(ann.getClasses())))
			       .map(t->field.getAccessor().getAnnotation((Class<Annotation>)t).map(ann->new LogicalAnnotation<>(ann, getAnnotation(t))))
			       .filter(Optional::isPresent)
			       .map(Optional::get)
			       .toList();
	}
	
	@SuppressWarnings("unchecked")
	private AnnotationLogic<Annotation> getAnnotation(Class<?> t){
		try{
			Field logic=t.getField("LOGIC");
			
			if(!(logic.getGenericType() instanceof ParameterizedType parmType&&
			     AnnotationLogic.class.equals(parmType.getRawType())&&
			     Arrays.equals(parmType.getActualTypeArguments(), new Type[]{t}))){
				
				throw new ClassCastException(logic+" is not a type of "+AnnotationLogic.class.getName()+"<"+t.getName()+">");
			}
			
			return (AnnotationLogic<Annotation>)logic.get(null);
		}catch(NoSuchFieldException|IllegalAccessException e){
			throw new RuntimeException("Class "+t.getName()+" does not contain an AnnotationLogic LOGIC field", e);
		}
	}
	
	
	private static final RegistryNode.Registry REGISTRY=new RegistryNode.Registry();
	
	static{
		REGISTRY.register(new RegistryNode(){
			@Override
			public boolean canCreate(Type type, GetAnnotation annotations){
				return annotations.isPresent(IOType.Dynamic.class);
			}
			@Override
			public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
				if(field.hasAnnotation(IOValue.Reference.class)){
					throw new NotImplementedException();
				}
				return new IOFieldDynamicInlineObject<>(field);
			}
		});
		REGISTRY.register(new RegistryNode(){
			@Override
			public boolean canCreate(Type type, GetAnnotation annotations){
				return IOFieldPrimitive.isPrimitive(type);
			}
			@Override
			public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
				return IOFieldPrimitive.make(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf<>(Enum.class){
			@Override
			public <T extends IOInstance<T>> IOField<T, Enum> create(FieldAccessor<T> field, GenericContext genericContext){
				return new IOFieldEnum<>(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf<>(INumber.class){
			@Override
			public <T extends IOInstance<T>> IOField<T, INumber> create(FieldAccessor<T> field, GenericContext genericContext){
				return new IOFieldNumber<>(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf<>(byte[].class){
			@Override
			public <T extends IOInstance<T>> IOField<T, byte[]> create(FieldAccessor<T> field, GenericContext genericContext){
				return new IOFieldByteArray<>(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf<>(boolean[].class){
			@Override
			public <T extends IOInstance<T>> IOField<T, boolean[]> create(FieldAccessor<T> field, GenericContext genericContext){
				return new IOFieldBooleanArray<>(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf<>(float[].class){
			@Override
			public <T extends IOInstance<T>> IOField<T, float[]> create(FieldAccessor<T> field, GenericContext genericContext){
				return new IOFieldFloatArray<>(field);
			}
		});
		REGISTRY.register(new RegistryNode(){
			@Override
			public boolean canCreate(Type type, GetAnnotation annotations){
				var raw=Utils.typeToRaw(type);
				if(!raw.isArray()) return false;
				return IOInstance.isManaged(raw.componentType());
			}
			@Override
			public <T extends IOInstance<T>> IOField<T, ?> create(FieldAccessor<T> field, GenericContext genericContext){
				return new IOFieldInstanceArray<>(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf<>(String.class){
			@Override
			public <T extends IOInstance<T>> IOField<T, String> create(FieldAccessor<T> field, GenericContext genericContext){
				return new IOFieldInlineString<>(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf<>(String[].class){
			@Override
			public <T extends IOInstance<T>> IOField<T, String[]> create(FieldAccessor<T> field, GenericContext genericContext){
				return new IOFieldStringArray<>(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf<>(IOInstance.class){
			@Override
			public <T extends IOInstance<T>> IOField<T, ? extends IOInstance> create(FieldAccessor<T> field, GenericContext genericContext){
				Class<?> raw      =field.getType();
				var      unmanaged=!IOInstance.isManaged(raw);
				
				if(unmanaged){
					return new IOFieldUnmanagedObjectReference<>(field);
				}
				if(field.hasAnnotation(IOValue.Reference.class)){
					return new IOFieldObjectReference<>(field);
				}
				return new IOFieldInlineObject<>(field);
			}
		});
	}
	
	protected RegistryNode.Registry registry(){
		return REGISTRY;
	}
	protected Set<Class<? extends Annotation>> activeAnnotations(){
		return Set.of(
			IOValue.class,
			IODependency.class,
			IONullability.class,
			IOType.class
		);
	}
	
}
