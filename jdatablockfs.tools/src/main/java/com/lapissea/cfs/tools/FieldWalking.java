package com.lapissea.cfs.tools;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.util.UtilL;

import java.io.IOException;
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
	
}
