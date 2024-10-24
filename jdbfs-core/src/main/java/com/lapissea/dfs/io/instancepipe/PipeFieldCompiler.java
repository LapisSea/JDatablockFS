package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;

import java.util.List;
import java.util.Objects;

public interface PipeFieldCompiler<T extends IOInstance<T>, E extends Exception>{
	
	record Result<T extends IOInstance<T>>(List<IOField<T, ?>> fields, Object builderMetadata){
		public Result(List<IOField<T, ?>> fields){ this(fields, null); }
		public Result{
			Objects.requireNonNull(fields);
		}
	}
	
	default Result<T> compile(Struct<T> type, FieldSet<T> structFields) throws E{ return compile(type, structFields, false); }
	Result<T> compile(Struct<T> type, FieldSet<T> structFields, boolean testRun) throws E;
}
