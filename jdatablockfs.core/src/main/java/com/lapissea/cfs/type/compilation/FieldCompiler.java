package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.type.FieldSet;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.*;
import com.lapissea.cfs.type.field.annotations.*;
import com.lapissea.util.PairM;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import ru.vyarus.java.generics.resolver.GenericsResolver;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Function.identity;

@SuppressWarnings("rawtypes")
public class FieldCompiler{
	
	private enum FieldAccess{
		UNSAFE,
		VAR_HANDLE,
		REFLECTION
	}
	
	private static final FieldAccess FIELD_ACCESS=GlobalConfig.configEnum("fieldAccess", Runtime.version().feature()<=18?FieldAccess.UNSAFE:FieldAccess.VAR_HANDLE);
	
	protected record LogicalAnnotation<T extends Annotation>(T annotation, AnnotationLogic<T> logic){}
	
	private static final FieldCompiler INST=new FieldCompiler();
	
	public static FieldCompiler create(){
		return INST;
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
	
	/**
	 * Scans an unmanaged struct for
	 */
	public <T extends IOInstance.Unmanaged<T>> FieldSet<T> compileStaticUnmanaged(Struct.Unmanaged<T> struct){
		var type=struct.getType();
		
		var methods=type.getDeclaredMethods();
		
		var valueDefs=Arrays.stream(methods)
		                    .filter(m->m.isAnnotationPresent(IOValueUnmanaged.class))
		                    .sorted(Comparator.comparingInt(m->-m.getAnnotation(IOValueUnmanaged.class).index()))
		                    .toList();
		if(valueDefs.isEmpty()) return FieldSet.of();
		
		var err=valueDefs.stream()
		                 .collect(Collectors.groupingBy(m->m.getAnnotation(IOValueUnmanaged.class).index()))
		                 .values()
		                 .stream()
		                 .filter(l->l.size()>1)
		                 .map(l->l.stream()
		                          .map(Method::getName)
		                          .collect(Collectors.joining(", ")))
		                 .collect(Collectors.joining("\t\n"));
		
		if(!err.isEmpty()){
			throw new MalformedStructLayout(type.getSimpleName()+" methods with duplicated indices:\n"+err);
		}
		
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
	
	/**
	 * Detects and generates fields from {@link Struct} type class data and all its super types.
	 *
	 * @return a {@link FieldSet} containing all {@link IOValue} fields
	 */
	public <T extends IOInstance<T>> FieldSet<T> compile(Struct<T> struct){
		List<FieldAccessor<T>> accessor=scanFields(struct);
		
		List<AnnotatedField<T>> fields=new ArrayList<>(Math.max(accessor.size()*2, accessor.size()+5));//Give extra capacity for virtual fields
		for(var a : accessor){
			var f=registry().create(a, null);
			fields.add(new AnnotatedField<>(f, scanAnnotations(f)));
		}
		
		generateVirtualFields(fields, struct);
		
		validate(fields);
		
		initLateData(fields);
		
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
				if(!dependencies.add(e)){
					//TODO: decide if duplicated dependencies should be tolerated
//					throw new MalformedStructLayout("Duplicate dependency "+e.getAccessor());
				}
			}
		}
		return dependencies;
	}
	
	private <T extends IOInstance<T>> void validate(List<AnnotatedField<T>> parsed){
		for(var pair : parsed){
			var nam=pair.field.getName();
			for(char c : new char[]{'.', '/', '\\', ' '}){
				if(nam.indexOf(c)!=-1){
					throw new MalformedStructLayout("Character '"+c+"' is not allowed in field name \""+nam+"\"! ");
				}
			}
			
			var field=pair.field.getAccessor();
			for(var ann : pair.annotations){
				ann.logic().validate(field, ann.annotation());
			}
		}
	}
	
	private <T extends IOInstance<T>> void initLateData(List<AnnotatedField<T>> fields){
		for(var pair : fields){
			var depAn=pair.annotations;
			var field=pair.field;
			
			field.initLateData(FieldSet.of(generateDependencies(fields, depAn, field)));
		}
	}
	
	private <T extends IOInstance<T>> void generateVirtualFields(List<AnnotatedField<T>> parsed, Struct<T> struct){
		
		Map<VirtualFieldDefinition.StoragePool, Integer> accessIndex    =new EnumMap<>(VirtualFieldDefinition.StoragePool.class);
		Map<VirtualFieldDefinition.StoragePool, Integer> primitiveOffset=new EnumMap<>(VirtualFieldDefinition.StoragePool.class);
		Map<String, FieldAccessor<T>>                    virtualData    =new HashMap<>();
		Map<String, FieldAccessor<T>>                    newVirtualData =new HashMap<>();
		
		List<AnnotatedField<T>> toRun=new ArrayList<>(parsed);
		
		do{
			for(var runAnn : toRun){
				for(var logicalAnn : runAnn.annotations){
					for(var s : logicalAnn.logic().injectPerInstanceValue(runAnn.field.getAccessor(), logicalAnn.annotation())){
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
							}else if(List.of(byte.class, boolean.class).contains(s.getType())){
								primitiveSize=1;
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
					       if(cp!=null&&(cp==IOInstance.class||!IOInstance.isInstance(cp))){
						       cp=null;
					       }
					       c=cp==c?null:cp;
					
					       return tmp;
				       }
			       })
			       .flatMap(c->Arrays.asList(c.getDeclaredFields()).iterator())
			       .filtered(f->f.isAnnotationPresent(type));
	}
	
	protected <T extends IOInstance<T>> List<FieldAccessor<T>> scanFields(Struct<T> struct){
		var cl=struct.getType();
		
		List<FieldAccessor<T>> fields    =new ArrayList<>();
		Set<Method>            usedFields=new HashSet<>();
		
		var ioMethods=allMethods(cl).filter(m->m.isAnnotationPresent(IOValue.class)).toList();
		
		for(Field field : deepFieldsByAnnotation(cl, IOValue.class)){
			try{
				Type type=getType(field);
				
				registry().requireCanCreate(type, field::getAnnotation);
				field.setAccessible(true);
				
				String fieldName=getFieldName(field);
				
				Function<String, Optional<Method>> getMethod=prefix->{
					for(Method m : ioMethods){
						if(checkMethod(fieldName, prefix, m)) return Optional.of(m);
					}
					return Optional.empty();
				};
				
				var getter=calcGetPrefixes(field).map(getMethod).filter(Optional::isPresent).map(Optional::get).findAny();
				var setter=getMethod.apply("set");
				
				getter.ifPresent(usedFields::add);
				setter.ifPresent(usedFields::add);
				
				fields.add(switch(FIELD_ACCESS){
					case UNSAFE -> UnsafeAccessor.make(struct, field, getter, setter, fieldName, type);
					case VAR_HANDLE -> VarHandleAccessor.make(struct, field, getter, setter, fieldName, type);
					case REFLECTION -> ReflectionAccessor.make(struct, field, getter, setter, fieldName, type);
				});
			}catch(Throwable e){
				throw new MalformedStructLayout("Failed to scan field #"+field.getName(), e);
			}
		}
		
		var hangingMethods=ioMethods.stream().filter(method->!usedFields.contains(method)).toList();
		
		Map<String, PairM<Method, Method>> transientFieldsMap=new HashMap<>();
		
		for(Method hangingMethod : hangingMethods){
			calcGetPrefixes(hangingMethod).map(p->getMethodFieldName(p, hangingMethod)).filter(Optional::isPresent).map(Optional::get)
			                              .findFirst().ifPresent(s->transientFieldsMap.computeIfAbsent(s, n->new PairM<>()).obj1=hangingMethod);
			getMethodFieldName("set", hangingMethod).ifPresent(s->transientFieldsMap.computeIfAbsent(s, n->new PairM<>()).obj2=hangingMethod);
		}
		
		var errors=transientFieldsMap.entrySet()
		                             .stream()
		                             .filter(e->e.getValue().obj1==null||e.getValue().obj2==null)
		                             .map(e->Map.of("fieldName", e.getKey(), "getter", e.getValue().obj1!=null, "setter", e.getValue().obj2!=null))
		                             .toList();
		if(!errors.isEmpty()){
			throw new MalformedStructLayout("Invalid transient (getter+setter, no value) IO field for "+cl.getName()+":\n"+TextUtil.toTable(errors));
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
		
		fields.sort(Comparator.naturalOrder());
		
		for(var e : transientFieldsMap.entrySet()){
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
			
			UtilL.addRemainSorted(fields, FunctionalReflectionAccessor.make(struct, name, getter, setter, annotations, type));
		}
		
		return fields;
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
			if(mName.length()<=prefix.length()||Character.isLowerCase(mName.charAt(prefix.length()))) return Optional.empty();
			StringBuilder name=new StringBuilder(mName.length()-prefix.length());
			name.append(mName, prefix.length(), mName.length());
			name.setCharAt(0, Character.toLowerCase(name.charAt(0)));
			return Optional.of(name.toString());
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
			
			type=SyntheticParameterizedType.of(raw, parms);
		}
		var rt=Utils.typeToRaw(type);
		if(rt.isInterface()){
			var impl=rt.getAnnotation(IOValue.OverrideType.DefaultImpl.class);
			if(impl!=null){
				var      rawType=SyntheticParameterizedType.generalize(type);
				Class<?> raw    =impl.value();
				Type[]   parms  =rawType.getActualTypeArguments();
				type=SyntheticParameterizedType.of(raw, parms);
			}
		}
		
		return type;
	}
	
	private Stream<Method> allMethods(Class<?> clazz){
		return Stream.iterate(clazz, Objects::nonNull, (UnaryOperator<Class<?>>)Class::getSuperclass)
		             .flatMap(c->Arrays.stream(c.getDeclaredMethods()));
	}
	
	private static final class LogicalAnnType{
		private final Class<Annotation>           type;
		private       AnnotationLogic<Annotation> logic;
		
		@SuppressWarnings("unchecked")
		private LogicalAnnType(Class<?> type){
			this.type=(Class<Annotation>)type;
		}
		
		public AnnotationLogic<Annotation> logic(){
			if(logic==null) logic=getAnnotationLogic(type);
			return logic;
		}
		
		@SuppressWarnings("unchecked")
		private AnnotationLogic<Annotation> getAnnotationLogic(Class<?> t){
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
	}
	
	private final List<LogicalAnnType> annotationScan=
		activeAnnotations()
			.stream()
			.flatMap(ann->Stream.concat(Stream.of(ann), Arrays.stream(ann.getClasses())))
			.map(LogicalAnnType::new)
			.toList();
	
	protected <T extends IOInstance<T>> List<LogicalAnnotation<Annotation>> scanAnnotations(IOField<T, ?> field){
		return annotationScan.stream()
		                     .map(logTyp->field.getAccessor()
		                                       .getAnnotation(logTyp.type)
		                                       .map(ann->new LogicalAnnotation<>(ann, logTyp.logic())))
		                     .filter(Optional::isPresent)
		                     .map(Optional::get)
		                     .toList();
	}
	
	
	private static final CompletableFuture<RegistryNode.Registry> REGISTRY=FieldRegistry.make();
	
	protected RegistryNode.Registry registry(){return REGISTRY.join();}
	
	protected Set<Class<? extends Annotation>> activeAnnotations(){
		return Set.of(
			IOValue.class,
			IODependency.class,
			IONullability.class,
			IOType.class
		);
	}
	
}
