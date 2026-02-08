package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.field.fields.BitField;
import com.lapissea.dfs.type.field.fields.reflection.BitFieldMerger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BitMergerInspectRead extends FieldInspectRead{
	
	public record BitVal<T extends IOInstance<T>>(BitField<T, ?> f, Object val, int offset, int size){ }
	
	public record Value<T extends IOInstance<T>>(List<BitVal<T>> values){ }
	
	@Override
	public <T extends IOInstance<T>> FieldReader.Res<T, ?> read(ReadCtx<T> ctx) throws IOException{
		var vPos = readOrSkip(ctx);
		
		var field = (BitFieldMerger<T>)ctx.field;
		
		List<BitVal<T>> values = new ArrayList<>();
		int             pos    = 0;
		for(BitField<T, ?> f : field.fieldGroup()){
			var val  = f.get(ctx.ioPool, ctx.inst);
			var size = Math.toIntExact(f.getSizeDescriptor().requireFixed(WordSpace.BIT));
			values.add(new BitVal<>(f, val, pos, size));
			pos += size;
		}
		
		return FieldReader.res(ctx.field, new Value<>(values), vPos);
	}
}
