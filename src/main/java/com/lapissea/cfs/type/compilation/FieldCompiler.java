package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.SyntheticParameterizedType;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.type.FieldSet;
import com.lapissea.cfs.type.GetAnnotation;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.FunctionalReflectionAccessor;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.cfs.type.field.access.ReflectionAccessor;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.PairM;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.field.VirtualFieldDefinition.StoragePool.*;
import static java.util.function.Function.*;

public abstract class FieldCompiler{
	
	protected record LogicalAnnotation<T extends Annotation>(T annotation, AnnotationLogic<T> logic){}
	
	public static FieldCompiler create(){
		return new ReflectionFieldCompiler();
	}
	
	private static record AnnotatedField<T extends IOInstance<T>>(
		IOField<T, ?> field,
		List<LogicalAnnotation<Annotation>> annotations
	) implements Comparable<AnnotatedField<T>>{
		@Override
		public int compareTo(AnnotatedField<T> o){
			return field().getAccessor().compareTo(o.field.getAccessor());
		}
	}
	
	public <T extends IOInstance<T>> FieldSet<T, ?> compile(Struct<T> struct){
		var fields=scanFields(struct).stream().map(f->new AnnotatedField<>(f, scanAnnotations(f))).collect(Collectors.toList());
		
		generateVirtualFields(fields, struct);
		
		validate(fields);
		
		for(var pair : fields){
			var depAn=pair.annotations;
			var field=pair.field;
			
			field.initLateData(new FieldSet<>(generateDependencies(fields, depAn, field)),
			                   fields.stream()
			                         .flatMap(f->f.annotations.stream()
			                                                  .flatMap(an->an.logic.getHints(an.annotation)
			                                                                       .map(h->h.target()==null?
			                                                                               new IOField.UsageHint(h.type(), f.field.getName()):
			                                                                               h)
			                                                  )
			                         )
			                         .filter(t->t.target().equals(field.getName()))
			                         .map(IOField.UsageHint::type)
			);
		}
		
		return new FieldSet<>(fields.stream().map(AnnotatedField::field));
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
			var field=pair.field.getAccessor();
			pair.annotations.forEach(ann->ann.logic().validate(field, ann.annotation()));
		}
	}
	
	private <T extends IOInstance<T>> void generateVirtualFields(List<AnnotatedField<T>> parsed, Struct<T> struct){
		
		Map<VirtualFieldDefinition.StoragePool, Integer> accessIndex   =new EnumMap<>(VirtualFieldDefinition.StoragePool.class);
		Map<String, IFieldAccessor<T>>                   virtualData   =new HashMap<>();
		Map<String, IFieldAccessor<T>>                   newVirtualData=new HashMap<>();
		
		List<AnnotatedField<T>> toRun=new ArrayList<>(parsed);
		
		while(true){
			for(var pair : toRun){
				for(var logicalAnn : pair.annotations){
					for(var s : logicalAnn.logic().injectPerInstanceValue(pair.field.getAccessor(), logicalAnn.annotation())){
						var existing=virtualData.get(s.getName());
						if(existing!=null){
							if(!existing.getGenericType().equals(s.getType())){
								throw new MalformedStructLayout("Virtual field "+existing.getName()+" already defined but has a type conflict of "+existing.getGenericType()+" and "+s.getType());
							}
							continue;
						}
						IFieldAccessor<T> accessor=new VirtualAccessor<>(
							struct,
							(VirtualFieldDefinition<T, Object>)s,
							accessIndex.compute(s.getStoragePool(), (k, v)->{
								if(k==NONE) return -1;
								else{
									return v==null?0:v+1;
								}
							}));
						virtualData.put(s.getName(), accessor);
						newVirtualData.put(s.getName(), accessor);
					}
				}
			}
			toRun.clear();
			for(var virtual : newVirtualData.values()){
				var field    =registry().create(virtual);
				var annotated=new AnnotatedField<>(field, scanAnnotations(field));
				toRun.add(annotated);
				UtilL.addRemainSorted(parsed, annotated);
			}
			newVirtualData.clear();
			if(toRun.isEmpty()) break;
		}
	}
	
	protected abstract RegistryNode.Registry registry();
	
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
	
	protected <T extends IOInstance<T>> List<IOField<T, ?>> scanFields(Struct<T> struct){
		var cl=struct.getType();
		
		List<IFieldAccessor<T>> fields    =new ArrayList<>();
		List<Method>            usedFields=new ArrayList<>();
		
		var registry=registry();
		
		for(Field field : deepFieldsByAnnotation(cl, IOValue.class)){
			Type type=getType(field);
			
			registry.requireCanCreate(type);
			field.setAccessible(true);
			
			String fieldName=getFieldName(field);
			
			Function<String, Optional<Method>> getMethod=prefix->scanMethod(cl, m->checkMethod(fieldName, prefix, m));
			
			var getter=getMethod.apply("get");
			var setter=getMethod.apply("set");
			
			getter.ifPresent(usedFields::add);
			setter.ifPresent(usedFields::add);
			
			IFieldAccessor<T> accessor;
			if(type instanceof Class<?> c&&UtilL.instanceOf(c, INumber.class)) accessor=new ReflectionAccessor.INum<>(struct, field, getter, setter, fieldName, type);
			else accessor=new ReflectionAccessor<>(struct, field, getter, setter, fieldName, type);
			
			UtilL.addRemainSorted(fields, accessor);
		}
		
		var hangingMethods=scanMethods(cl, method->method.isAnnotationPresent(IOValue.class)&&!usedFields.contains(method)).toList();
		
		Map<String, PairM<Method, Method>> transientFieldsMap=new HashMap<>();
		
		for(Method hangingMethod : hangingMethods){
			getMethodFieldName("get", hangingMethod).ifPresent(s->transientFieldsMap.computeIfAbsent(s, n->new PairM<>()).obj1=hangingMethod);
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
		                               .map(Method::toString)
		                               .collect(Collectors.joining("\n"));
		if(!unusedWaning.isEmpty()){
			throw new MalformedStructLayout("There are unused or invalid methods marked with "+IOValue.class.getSimpleName()+"\n"+unusedWaning);
		}
		
		transientFieldsMap.entrySet().stream().<IFieldAccessor<T>>map(e->{
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
			
			if(UtilL.instanceOf(p.obj1.getReturnType(), INumber.class)) return new FunctionalReflectionAccessor.INum<>(struct, annotations, getter, setter, name, type);
			else return new FunctionalReflectionAccessor<>(struct, annotations, getter, setter, name, type);
		}).forEach(fields::add);
		
		
		List<IOField<T, ?>> parsed=new ArrayList<>(fields.size());
		for(var f : fields){
			parsed.add(registry.create(f));
		}
		
		return parsed;
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
			Class<?> raw;
			Type[]   parms;
			if(type instanceof ParameterizedType parmType){
				raw=(Class<?>)parmType.getRawType();
				parms=parmType.getActualTypeArguments();
			}else{
				raw=(Class<?>)type;
				parms=new Type[0];
			}
			
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
			       .map(t->field.getAccessor().getAnnotation((Class<? extends Annotation>)t).map(ann->{
				       try{
					       Field logic=t.getField("LOGIC");
					
					       if(!(logic.getGenericType() instanceof ParameterizedType parmType&&
					            AnnotationLogic.class.equals(parmType.getRawType())&&
					            Arrays.equals(parmType.getActualTypeArguments(), new Type[]{t}))){
						
						       throw new ClassCastException(logic+" is not a type of "+AnnotationLogic.class.getName()+"<"+t.getName()+">");
					       }
					
					       return new LogicalAnnotation<>(ann, (AnnotationLogic<Annotation>)logic.get(null));
				       }catch(NoSuchFieldException|IllegalAccessException e){
					       throw new RuntimeException("Class "+t.getName()+" does not contain an AnnotationLogic LOGIC field", e);
				       }
			       })).filter(Optional::isPresent).map(Optional::get).toList();
	}
	
	protected abstract Set<Class<? extends Annotation>> activeAnnotations();
	
}
