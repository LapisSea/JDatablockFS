package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Index;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.DepSort;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.cfs.type.field.access.IFieldAccessor;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.fields.reflection.BitFieldMerger;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;

import java.util.*;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IOFieldTools{
	
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
			if(field.getSizeDescriptor().getWordSpace()==WordSpace.BIT){
				bitBuilder.add((IOField.Bit<T, ?>)field);
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
			return new DepSort<>(fields, f->f.getDependencies().stream().mapToInt(fields::indexOf))
				       .sort(Comparator.comparingInt((IOField<T, ?> f)->f.getSizeDescriptor().getWordSpace().sortOrder)
				                       .thenComparingInt(f->f.getSizeDescriptor().getFixed().isPresent()?0:1)
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
	
	public static <T extends IOInstance<T>> IOField<T, NumberSize> getDynamicSize(IFieldAccessor<T> field){
		Optional<String> dynSiz=Stream.of(
			Optional.ofNullable(field.getAnnotation(IODependency.NumSize.class)).map(IODependency.NumSize::value),
			Optional.ofNullable(field.getAnnotation(IODependency.VirtualNumSize.class)).map(IODependency.VirtualNumSize::name)
		).filter(Optional::isPresent).map(Optional::get).findAny();
		
		if(dynSiz.isEmpty()) return null;
		var opt=field.getStruct().getFieldsByType(NumberSize.class).filter(f->f.getName().equals(dynSiz.get())).findAny();
		if(opt.isEmpty()) throw new ShouldNeverHappenError("This should have been checked in annotation logic");
		return opt.get();
	}
	
	public static <T extends IOInstance<T>> OptionalLong sumVarsIfAll(List<? extends IOField<T, ?>> fields, Function<SizeDescriptor<T>, OptionalLong> mapper){
		return fields.stream().map(IOField::getSizeDescriptor).map(mapper).reduce(OptionalLong.of(0), Utils::addIfBoth);
	}
	public static <T extends IOInstance<T>> long sumVars(List<? extends IOField<T, ?>> fields, ToLongFunction<SizeDescriptor<T>> mapper){
		return fields.stream().map(IOField::getSizeDescriptor).mapToLong(mapper).sum();
	}
}
