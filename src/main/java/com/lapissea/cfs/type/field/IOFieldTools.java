package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Index;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.DepSort;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.access.FieldAccessor;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.util.NotNull;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;

import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NOT_NULL;

public class IOFieldTools{
	
	public static <T extends IOInstance<T>> Function<List<IOField<T, ?>>, List<IOField<T, ?>>> streamStep(Function<Stream<IOField<T, ?>>, Stream<IOField<T, ?>>> map){
		return list->map.apply(list.stream()).toList();
	}
	
	public static <T extends IOInstance<T>> List<IOField<T, ?>> stepFinal(List<IOField<T, ?>> data, Iterable<Function<List<IOField<T, ?>>, List<IOField<T, ?>>>> steps){
		List<IOField<T, ?>> d=data;
		for(Function<List<IOField<T, ?>>, List<IOField<T, ?>>> step : steps){
			d=step.apply(d);
		}
		return List.copyOf(d);
	}
	
	public static <T extends IOInstance<T>> List<IOField<T, ?>> mergeBitSpace(List<IOField<T, ?>> mapData){
		var result    =new LinkedList<IOField<T, ?>>();
		var bitBuilder=new LinkedList<IOField.Bit<T, ?>>();
		
		Runnable pushBuilt=()->{
			switch(bitBuilder.size()){
				case 0 -> {}
				case 1 -> result.add(bitBuilder.remove(0));
				default -> {
					result.add(new BitFieldMerger<>(bitBuilder));
					bitBuilder.clear();
				}
			}
		};
		
		for(IOField<T, ?> field : mapData){
			if(field instanceof IOField.Bit<?, ?> bit){
				bitBuilder.add((IOField.Bit<T, ?>)bit);
				continue;
			}
			pushBuilt.run();
			result.add(field);
		}
		pushBuilt.run();
		
		return result;
	}
	
	public static <T extends IOInstance<T>> List<IOField<T, ?>> dependencyReorder(List<IOField<T, ?>> fields){
		return computeDependencyIndex(fields).mapData(fields);
	}
	
	public static <T extends IOInstance<T>> Index computeDependencyIndex(List<IOField<T, ?>> fields){
		try{
			return new DepSort<>(fields, f->f.getDependencies()
			                                 .stream()
			                                 .mapToInt(o->IntStream.range(0, fields.size())
			                                                       .filter(i->fields.get(i).getAccessor()==o.getAccessor())
			                                                       .findAny()
			                                                       .orElseThrow())
			).sort(Comparator.comparingInt((IOField<T, ?> f)->f.getSizeDescriptor().getWordSpace().sortOrder)
			                 .thenComparingInt(f->f.getSizeDescriptor().hasFixed()?0:1)
			                 .thenComparing(f->switch(f.getDependencies().size()){
				                 case 0 -> "";
				                 case 1 -> f.getDependencies().get(0).getName();
				                 default -> f.getDependencies().stream().map(IOField::getName).collect(Collectors.joining("+"));
			                 })
			);
		}catch(DepSort.CycleException e){
			throw new MalformedStructLayout("Field dependency cycle detected:\n"+TextUtil.toTable(e.cycle.mapData(fields)), e);
		}
	}
	
	public static <T extends IOInstance<T>> IOField<T, NumberSize> getDynamicSize(FieldAccessor<T> field){
		Optional<String> dynSiz=Stream.of(
			field.getAnnotation(IODependency.NumSize.class).map(IODependency.NumSize::value),
			field.getAnnotation(IODependency.VirtualNumSize.class).map(e->IODependency.VirtualNumSize.Logic.getName(field, e))
		).filter(Optional::isPresent).map(Optional::get).findAny();
		
		if(dynSiz.isEmpty()) return null;
		var opt=field.getDeclaringStruct().getFields().exact(NumberSize.class, dynSiz.get());
		if(opt.isEmpty()) throw new ShouldNeverHappenError("This should have been checked in annotation logic");
		return opt.get();
	}
	
	public static <T extends IOInstance<T>> OptionalLong sumVarsIfAll(Collection<? extends IOField<T, ?>> fields, Function<SizeDescriptor<T>, OptionalLong> mapper){
		return fields.stream().map(IOField::getSizeDescriptor).map(mapper).reduce(OptionalLong.of(0), Utils::addIfBoth);
	}
	public static <T extends IOInstance<T>> long sumVars(Collection<? extends IOField<T, ?>> fields, ToLongFunction<SizeDescriptor<T>> mapper){
		return fields.stream().map(IOField::getSizeDescriptor).mapToLong(mapper).sum();
	}
	
	public static <T extends IOInstance<T>> WordSpace minWordSpace(Collection<? extends IOField<T, ?>> fields){
		return fields.stream().map(IOField::getSizeDescriptor).map(SizeDescriptor::getWordSpace).reduce(WordSpace::min).orElse(WordSpace.MIN);
	}
	
	public static <T extends IOInstance<T>> String makeArrayLenName(FieldAccessor<T> field){
		return field.getName()+".len";
	}
	public static <T extends IOInstance<T>> String makeGenericIDFieldName(FieldAccessor<T> field){
		return field.getName()+".typeID";
	}
	
	public static IONullability.Mode getNullability(FieldAccessor<?> field){
		return getNullability(field, NOT_NULL);
	}
	public static IONullability.Mode getNullability(FieldAccessor<?> field, IONullability.Mode defaultMode){
		return field.getAnnotation(IONullability.class).map(IONullability::value).orElse(defaultMode);
	}
	public static <T extends IOInstance<T>> String makeRefName(FieldAccessor<T> accessor){
		
		return accessor.getName()+".ref";
	}
	
	public static <E extends Annotation> E makeAnnotation(Class<E> annotationType, @NotNull Map<String, Object> values){
		Objects.requireNonNull(values);
		Class<?>[] interfaces=annotationType.getInterfaces();
		if(!annotationType.isAnnotation()||interfaces.length!=1||interfaces[0]!=Annotation.class){
			throw new IllegalArgumentException(annotationType.getName()+" not an annotation");
		}
		
		var safeValues=Arrays.stream(annotationType.getDeclaredMethods()).map(element->{
			String elementName=element.getName();
			if(values.containsKey(elementName)){
				Class<?> returnType=element.getReturnType();
				
				if(returnType.isPrimitive()){
					if(returnType==boolean.class) returnType=Boolean.class;
					else if(returnType==char.class) returnType=Character.class;
					else if(returnType==float.class) returnType=Float.class;
					else if(returnType==double.class) returnType=Double.class;
					else if(returnType==byte.class) returnType=Byte.class;
					else if(returnType==short.class) returnType=Short.class;
					else if(returnType==int.class) returnType=Integer.class;
					else if(returnType==long.class) returnType=Long.class;
					else throw new ShouldNeverHappenError(returnType.toString());
				}
				
				if(returnType.isInstance(values.get(elementName))){
					return Map.entry(elementName, values.get(elementName));
				}else{
					throw new IllegalArgumentException("Incompatible type for "+elementName);
				}
			}else{
				if(element.getDefaultValue()!=null){
					return Map.entry(elementName, element.getDefaultValue());
				}else{
					throw new IllegalArgumentException("Missing value "+elementName);
				}
			}
		}).collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
		
		int hash=values.entrySet().stream().mapToInt(element->{
			int    res;
			Object val=element.getValue();
			if(val.getClass().isArray()){
				res=1;
				for(int i=0;i<Array.getLength(val);i++){
					var el=Array.get(val, i);
					res=31*res+Objects.hashCode(el);
				}
			}else res=Objects.hashCode(val);
			return (127*element.getKey().hashCode())^res;
		}).sum();
		
		class FakeAnnotation implements Annotation, InvocationHandler{
			
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable{
				if(safeValues.containsKey(method.getName())){
					return safeValues.get(method.getName());
				}
				return method.invoke(this, args);
			}
			
			@Override
			public Class<? extends Annotation> annotationType(){
				return annotationType;
			}
			
			@Override
			public boolean equals(Object other){
				if(this==other) return true;
				if(!annotationType.isInstance(other)) return false;
				
				var that   =annotationType.cast(other);
				var thatAnn=that.annotationType();
				
				return safeValues.entrySet().stream().allMatch(element->{
					try{
						var thatVal=thatAnn.getMethod(element.getKey()).invoke(that);
						return Objects.deepEquals(element.getValue(), thatVal);
					}catch(ReflectiveOperationException e){
						throw new RuntimeException(e);
					}
				});
			}
			
			@Override
			public int hashCode(){
				return hash;
			}
			
			@Override
			public String toString(){
				return '@'+annotationType.getName()+TextUtil.toString(safeValues);
			}
		}
		
		//noinspection unchecked
		return (E)Proxy.newProxyInstance(annotationType.getClassLoader(),
		                                 new Class[]{annotationType},
		                                 new FakeAnnotation());
	}
}
