package com.lapissea.dfs.inspect.display.grid.read;

import com.lapissea.dfs.inspect.display.grid.FieldReader;
import com.lapissea.dfs.type.IOInstance;

import java.io.IOException;

public class BitMergerInspectRead extends FieldInspectRead{
	
	@Override
	public <T extends IOInstance<T>> FieldReader.Res<T, ?> read(ReadCtx<T> ctx) throws IOException{
		readOrSkip(ctx);
		
		return FieldReader.res(ctx.field, null, defaultPos(ctx.src, 0, 0));
	}
}
