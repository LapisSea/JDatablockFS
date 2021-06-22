package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.exceptions.FixedFormatNotSupportedException;
import com.lapissea.cfs.exceptions.MalformedStructLayout;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.IOFieldTools;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.lapissea.cfs.GlobalConfig.*;
import static com.lapissea.cfs.type.field.IOField.UsageHintType.*;
import static java.util.function.Predicate.*;

public class FixedContiguousStructPipe<T extends IOInstance<T>> extends StructPipe<T>{
	
	public static <T extends IOInstance<T>> StructPipe<T> of(Class<T> type){
		return of(Struct.of(type));
	}
	public static <T extends IOInstance<T>> StructPipe<T> of(Struct<T> struct){
		return of(FixedContiguousStructPipe.class, struct);
	}
	
	private Map<IOField<T, NumberSize>, NumberSize> maxValues;
	
	public FixedContiguousStructPipe(Struct<T> type){
		super(type);
		
		if(DEBUG_VALIDATION){
			getSizeDescriptor().requireFixed();
		}
	}
	@Override
	protected List<IOField<T, ?>> initFields(){
		
		Map<IOField<T, NumberSize>, List<IOField<T, ?>>> sizeDeps=
			getType().getFields().byType(NumberSize.class)
			         .filter(f->f.getUsageHints().contains(SIZE_DATA))
			         .collect(Collectors.toMap(
				         em->em,
				         em->getType().getFields()
				                      .stream()
				                      .filter(f->f.getDependencies().contains(em))
				                      .collect(Collectors.toList()))
			         );
		
		maxValues=sizeDeps.entrySet()
		                  .stream()
		                  .map(e->{
			                  var sizes=e.getValue()
			                             .stream()
			                             .mapToLong(v->v.getSizeDescriptor().requireMax())
			                             .distinct()
			                             .mapToObj(l->NumberSize.FLAG_INFO.stream()
			                                                              .filter(s->s.bytes==l)
			                                                              .findAny().orElseThrow())
			                             .toList();
			                  if(sizes.size()!=1) throw new MalformedStructLayout("inconsistent dependency sizes\n"+TextUtil.toNamedPrettyJson(e));
			                  return new AbstractMap.SimpleEntry<>(e.getKey(), sizes.get(0));
		                  })
		                  .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		
		for(IOField<T, NumberSize> f : maxValues.keySet()){
			if(!f.getDependencies().isEmpty()) throw new ShouldNeverHappenError("this value should not have deps?");
		}
		try{
			return IOFieldTools.stepFinal(
				getType().getFields()
				         .stream()
				         .map(f->sizeDeps.containsKey(f)?f:f.forceMaxAsFixedSize())
				         .collect(Collectors.toList()),
				List.of(
					IOFieldTools::dependencyReorder,
					list->list.stream().filter(not(sizeDeps::containsKey)).toList(),
					IOFieldTools::mergeBitSpace
				));
		}catch(FixedFormatNotSupportedException e){
			throw new MalformedStructLayout(getType().getType().getName()+" does not support fixed size layout because of "+e.getField(), e);
		}
	}
	@Override
	protected void doWrite(ChunkDataProvider provider, ContentWriter dest, T instance) throws IOException{
		maxValues.forEach((k, v)->k.set(instance, v));
		writeIOFields(provider, dest, instance);
	}
	
	@Override
	protected T doRead(ChunkDataProvider provider, ContentReader src, T instance) throws IOException{
		maxValues.forEach((k, v)->k.set(instance, v));
		readIOFields(provider, src, instance);
		return instance;
	}
}
