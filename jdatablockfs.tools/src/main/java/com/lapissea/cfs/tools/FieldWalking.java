package com.lapissea.cfs.tools;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

class FieldWalking{
	
	static IterablePP<BinaryDrawing.Range> chainRangeResolve(ChunkDataProvider cluster, Reference ref, int fieldOffset, int size){
		return IterablePP.nullTerminated(()->new Supplier<>(){
			int remaining=size;
			final ChunkChainIO io;
			
			{
				try{
					io=new ChunkChainIO(ref.getPtr().dereference(cluster));
					io.setPos(ref.getOffset()+fieldOffset);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
			
			@Override
			public BinaryDrawing.Range get(){
				try{
					while(remaining>0){
						var  cursorOff=io.calcCursorOffset();
						var  cursor   =io.getCursor();
						long cRem     =Math.min(remaining, cursor.getSize()-cursorOff);
						if(cRem==0){
							if(io.remaining()==0) return null;
							io.skip(cursor.getCapacity()-cursor.getSize());
							continue;
						}
						io.skip(cRem);
						remaining-=cRem;
						var start=cursor.dataStart()+cursorOff;
						return new BinaryDrawing.Range(start, start+cRem);
					}
					return null;
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
			}
		});
	}
	
	@SuppressWarnings("unchecked")
	static <T extends IOInstance<T>> void walkReferences(Cluster cluster, List<IOInstance<?>> stack,
	                                                     T instance, Reference reference, StructPipe<T> pipe,
	                                                     Consumer<Reference> referenceRecord) throws IOException{
		if(instance instanceof Chunk c){
			var off=cluster.getFirstChunk().getPtr().getValue();
			reference=new Reference(ChunkPointer.of(off), c.getPtr().getValue()-off);
		}
		if(reference==null||reference.isNull()) return;
		
		referenceRecord.accept(reference);
		
		try{
			if(stack.contains(instance)) return;
			stack.add(instance);
			
			var set=new HashSet<>(pipe.getSpecificFields());
			var tyo=pipe.getType().getFields();
			set.addAll(tyo);
			if(instance instanceof IOInstance.Unmanaged<?> u){
				u.listDynamicUnmanagedFields().map(f->(IOField<T, ?>)f).forEach(set::add);
			}
			
			for(IOField<T, ?> field : set){
				if(field instanceof IOField.Ref<?, ?> refO){
					IOField.Ref<T, T> refField=(IOField.Ref<T, T>)refO;
					walkReferences(cluster, stack, refField.get(instance), refField.getReference(instance), refField.getReferencedPipe(instance), referenceRecord);
					continue;
				}
				var acc=field.getAccessor();
				if(acc==null) continue;
				
				var type=acc.getType();
				
				if(acc.hasAnnotation(IOType.Dynamic.class)){
					var inst=acc.get(instance);
					if(inst!=null) type=inst.getClass();
				}
				
				if(type==ChunkPointer.class){
					var ch=(ChunkPointer)field.get(instance);
					if(!ch.isNull()){
						referenceRecord.accept(ch.makeReference());
					}
				}else{
					if(UtilL.instanceOf(type, IOInstance.class)){
						var inst=(IOInstance<?>)field.get(instance);
						if(inst!=null){
							walkReferences(cluster, stack, (T)inst, reference, StructPipe.of(pipe.getClass(), inst.getThisStruct()), referenceRecord);
						}
					}
				}
			}
		}finally{
			stack.remove(instance);
		}
	}
}
