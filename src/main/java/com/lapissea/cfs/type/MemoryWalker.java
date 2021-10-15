package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.util.LogUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

public class MemoryWalker{
	
	public <T extends IOInstance<T>> void walk(ChunkDataProvider cluster, T root, Reference instanceReference, StructPipe<T> pipe, UnsafeConsumer<Reference, IOException> consumer) throws IOException{
		walkStruct(cluster, new LinkedList<>(), root, instanceReference, pipe, consumer);
	}
	
	@SuppressWarnings("unchecked")
	private <T extends IOInstance<T>> void walkStruct(ChunkDataProvider cluster, List<IOInstance<?>> stack,
	                                                  T instance, Reference instanceReference, StructPipe<T> pipe,
	                                                  UnsafeConsumer<Reference, IOException> pointerRecord) throws IOException{
		var reference=instanceReference;
		if(instance instanceof Chunk c){
			var off=cluster.getFirstChunk().getPtr();
			reference=new Reference(off, c.getPtr().getValue()-off.getValue());
		}
		if(reference==null||reference.isNull()) return;
		try{
			if(stack.contains(instance)) return;
			stack.add(instance);
			
			var    fieldOffset=0L;
			
			Iterator<IOField<T, ?>> iterator;
			if(instance instanceof IOInstance.Unmanaged unmanaged){
				iterator=Stream.concat(pipe.getSpecificFields().stream(), unmanaged.listUnmanagedFields()).iterator();
			}else{
				iterator=(Iterator<IOField<T, ?>>)pipe.getSpecificFields().iterator();
			}
			
			while(iterator.hasNext()){
				IOField<T, ?> field=iterator.next();
				
				final long size;
				var        sizeDesc=field.getSizeDescriptor();
				size=sizeDesc.calcUnknown(instance);
				
				try{
					if(field instanceof IOField.Ref<?, ?> refO){
						IOField.Ref<T, T> refField=(IOField.Ref<T, T>)refO;
						var               ref     =refField.getReference(instance);
						walkStruct(cluster, stack, refField.get(instance), refField.getReference(instance), refField.getReferencedPipe(instance), pointerRecord);
						pointerRecord.accept(ref);
					}else if(UtilL.instanceOf(field.getAccessor().getType(), ChunkPointer.class)){
						
						var ch=(ChunkPointer)field.get(instance);
						
						if(!ch.isNull()){
							walkStruct(cluster, stack, ch.dereference(cluster), null, Chunk.PIPE, pointerRecord);
							pointerRecord.accept(ch.makeReference());
						}
					}else{
						var typ=field.getAccessor().getType();
						if(IOFieldPrimitive.isPrimitive(typ))continue;
						if(UtilL.instanceOf(typ, IOInstance.class)){
							var inst=(IOInstance<?>)field.get(instance);
							if(inst!=null){
								walkStruct(cluster, stack, (T)inst, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), inst.getThisStruct()), pointerRecord);
							}
							continue;
						}
						if(typ==String.class){
							continue;
						}
						if(field.getAccessor().hasAnnotation(IOType.Dynamic.class)){
							var inst=field.get(instance);
							if(inst==null) continue;
							
							if(inst instanceof IOInstance i){
								walkStruct(cluster, stack, i, reference.addOffset(fieldOffset), StructPipe.of(pipe.getClass(), i.getThisStruct()), pointerRecord);
								continue;
							}
							
							continue;
						}
						LogUtil.printlnEr("unamanaged draw type:", typ);
					}
				}finally{
					fieldOffset+=field.getSizeDescriptor().toBytes(size);
				}
			}
		}finally{
			stack.remove(instance);
		}
	}
	
	
}
