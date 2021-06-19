package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.chunk.ChunkDataProvider;
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
	
	public static <T extends IOInstance<T>> FixedContiguousStructPipe<T> of(Class<T> type){
		return of(Struct.of(type));
	}
	public static <T extends IOInstance<T>> FixedContiguousStructPipe<T> of(Struct<T> struct){
		return of(FixedContiguousStructPipe.class, struct);
	}
	
	private final List<IOField<T, ?>>                     ioFields;
	private final Map<IOField<T, NumberSize>, NumberSize> maxValues;
	
	public FixedContiguousStructPipe(Struct<T> type){
		super(type);
		
		Map<IOField<T, NumberSize>, List<IOField<T, ?>>> sizeDeps=
			type.getFields().byType(NumberSize.class)
			    .filter(f->f.getUsageHints().contains(DYNAMIC_SIZE_RESOLVE_DATA))
			    .collect(Collectors.toMap(
				    em->em,
				    em->type.getFields()
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
		
		ioFields=IOFieldTools.stepFinal(
			type.getFields()
			    .stream()
			    .map(f->sizeDeps.containsKey(f)?f:f.forceMaxAsFixedSize())
			    .collect(Collectors.toList()),
			List.of(
				IOFieldTools::dependencyReorder,
				list->list.stream().filter(not(sizeDeps::containsKey)).toList(),
				IOFieldTools::mergeBitSpace
			));
		
		if(DEBUG_VALIDATION){
			getSizeDescriptor().requireFixed();
		}
	}
	
	@Override
	public void write(ContentWriter dest, T instance) throws IOException{
		maxValues.forEach((k, v)->k.set(instance, v));
		
		for(IOField<T, ?> field : ioFields){
			if(DEBUG_VALIDATION){
				var  desc =field.getSizeDescriptor();
				long bytes=desc.toBytes(desc.requireFixed());
				
				var buf=dest.writeTicket(bytes).requireExact().submit();
				field.writeReported(buf, instance);
				try{
					buf.close();
				}catch(Exception e){
					throw new IOException("Field "+TextUtil.toString(field)+" was written incorrectly", e);
				}
			}else{
				field.writeReported(dest, instance);
			}
		}
	}
	
	@Override
	public T read(ChunkDataProvider provider, ContentReader src, T instance) throws IOException{
		maxValues.forEach((k, v)->k.set(instance, v));
		
		for(IOField<T, ?> field : ioFields){
			if(DEBUG_VALIDATION){
				var  desc =field.getSizeDescriptor();
				long bytes=desc.toBytes(desc.requireFixed());
				
				var buf=src.readTicket(bytes).requireExact().submit();
				field.readReported(provider, buf, instance);
				buf.close();
			}else{
				field.readReported(provider, src, instance);
			}
		}
		return instance;
	}
	
	@Override
	public List<IOField<T, ?>> getSpecificFields(){
		return ioFields;
	}
}
