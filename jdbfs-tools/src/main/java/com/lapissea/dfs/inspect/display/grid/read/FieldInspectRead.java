package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.inspect.display.grid.DataPos;
import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.tools.DrawUtils;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.VarPool;
import com.lapissea.dfs.type.field.IOField;

import java.io.IOException;

public interface FieldInspectRead{
	
	interface ValueReader{
		FieldReader.ResSet<?> read() throws IOException;
	}
	
	record ReferenceInfo(DataPos.Sized origin, DataPos ref, Class<?> type, ValueReader reader){ }
	
	record ReadResult<T extends IOInstance<T>, V>(FieldReader.Res<T, V> inlineRes, ReferenceInfo referenceInfo){
		
		public static <T extends IOInstance<T>, V> ReadResult<T, V> res(IOField<T, V> field, V value, DataPos.Sized pos){
			return new ReadResult<>(new FieldReader.Res<>(field, value, pos), null);
		}
		
		public ReadResult<T, V> withRef(DataPos.Sized origin, DataPos ref, Class<?> type, ValueReader reader){
			return new ReadResult<>(inlineRes, new ReferenceInfo(origin, ref, type, reader));
		}
		
	}
	
	default DataPos.Sized defaultPos(RandomIO src, long start, long end){
		ChunkPointer    ptr   = tryGetPtr(src);
		DrawUtils.Range range = DrawUtils.Range.fromStartEnd(start, end);
		return new DataPos.Sized(ptr, range);
	}
	
	default ChunkPointer tryGetPtr(RandomIO src){
		ChunkPointer ptr;
		if(src instanceof ChunkChainIO io){
			ptr = io.getCursor().getPtr();
		}else{
			ptr = ChunkPointer.NULL;
		}
		return ptr;
	}
	
	<T extends IOInstance<T>> ReadResult<T, ?> read(IOField<T, Object> field, VarPool<T> ioPool, DataProvider dataProvider, RandomIO src, T inst, GenericContext genericContext) throws IOException;
}
