package com.lapissea.cfs.type.compilation;

import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.objects.INumber;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.VirtualFieldDefinition;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.cfs.type.field.access.VirtualAccessor;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldEnum;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldNumber;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.util.UtilL;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

public class ReflectionFieldCompiler extends FieldCompiler{
	
	
	public static final RegistryNode.Registry REGISTRY=new RegistryNode.Registry();
	
	static{
		REGISTRY.register(new RegistryNode(){
			@Override
			public boolean canCreate(Type type){
				return IOFieldPrimitive.isPrimitive(type);
			}
			@Override
			public <T extends IOInstance<T>> IOField<T, ?> create(IFieldAccessor<T> field){
				return IOFieldPrimitive.make(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf(){
			@Override
			public Class<?> getType(){
				return Enum.class;
			}
			@Override
			public <T extends IOInstance<T>> IOField<T, ?> create(IFieldAccessor<T> field){
				return new IOFieldEnum<>(field);
			}
		});
		REGISTRY.register(new RegistryNode.InstanceOf(){
			@Override
			public Class<?> getType(){
				return INumber.class;
			}
			@Override
			public <T extends IOInstance<T>> IOField<T, ?> create(IFieldAccessor<T> field){
				return new IOFieldNumber<>(field);
			}
		});
	}
	
	@Override
	public <T extends IOInstance<T>> List<IOField<T, ?>> compile(Struct<T> struct){
		record AnnotatedField<T extends IOInstance<T>>(IOField<T, ?> field, List<LogicalAnnotation<Annotation>> annotations) implements Comparable<AnnotatedField<T>>{
			public AnnotatedField(IOField<T, ?> field){
				this(field, List.of());
			}
			@Override
			public int compareTo(AnnotatedField<T> o){
				return field().getAccessor().compareTo(o.field.getAccessor());
			}
		}
		var parsed=scanFields(struct).stream().map(f->new AnnotatedField<>(f, scanAnnotations(f))).collect(Collectors.toList());
		
		var dataContext=new AnnotationLogic.TypeData<>(struct.getType(), ()->parsed.stream().map(p->p.field.getAccessor()).iterator());
		
		{
			Map<String, IFieldAccessor<T>> virtualData=new HashMap<>();
			int                            accessIndex=0;
			for(var pair : parsed){
				var context=new AnnotationLogic.Context<>(pair.field.getAccessor(), dataContext);
				for(var logicalAnn : pair.annotations){
					for(VirtualFieldDefinition<T, ?> s : logicalAnn.logic().injectPerInstanceValue(context, logicalAnn.annotation())){
						var existing=virtualData.get(s.getName());
						if(existing!=null){
							if(!existing.getGenericType().equals(s.getType())){
								throw new MalformedStructLayout("Virtual field "+existing.getName()+" already defined but has a type conflict of "+existing.getGenericType()+" and "+s.getType());
							}
							continue;
						}
						int index;
						if(s.isStored()){
							index=accessIndex;
							accessIndex++;
						}else index=-1;
						virtualData.put(s.getName(), new VirtualAccessor<>(struct, (VirtualFieldDefinition<T, Object>)s, index));
					}
				}
			}
			for(var virtual : virtualData.values()){
				UtilL.addRemainSorted(parsed, new AnnotatedField<>(registry().create(virtual)));
			}
		}
		
		
		for(var pair : parsed){
			var depAn=pair.annotations;
			var field=pair.field;
			
			var context=new AnnotationLogic.Context<>(field.getAccessor(), dataContext);
			depAn.forEach(ann->ann.logic().validate(context, ann.annotation()));
			
			Collection<IOField<T, ?>> dependencies=new HashSet<>();
			
			
			for(var ann : depAn){
				ann.logic().validate(context, ann.annotation());
				
				var depNames=ann.logic().getDependencyValueNames(ann.annotation());
				if(depNames.size()==0) continue;
				
				var missingNames=depNames.stream()
				                         .filter(name->parsed.stream().noneMatch(f->f.field.getName().equals(name)))
				                         .collect(Collectors.joining(", "));
				if(!missingNames.isEmpty()) throw new MalformedStructLayout("Could not find dependencies "+missingNames+" on field "+field.getAccessor());
				
				for(String nam : depNames){
					IOField<T, ?> e=parsed.stream().filter(f->f.field.getName().equals(nam)).findAny().orElseThrow().field;
					if(!dependencies.add(e)) throw new MalformedStructLayout("Duplicate dependency "+e.getAccessor());
				}
			}
			field.initCommon(List.copyOf(dependencies));
		}
		
		List<IOField<T, ?>> fs=new ArrayList<>(parsed.size());
		
		for(var pair : parsed){
			fs.add(pair.field);
		}
		
		return List.copyOf(fs);
	}
	
	@Override
	protected RegistryNode.Registry registry(){
		return REGISTRY;
	}
	@Override
	protected Set<Class<? extends Annotation>> activeAnnotations(){
		return Set.of(IODependency.class);
	}
	
}
