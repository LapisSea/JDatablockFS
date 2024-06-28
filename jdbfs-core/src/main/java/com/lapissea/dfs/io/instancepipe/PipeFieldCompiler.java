package com.lapissea.dfs.io.instancepipe;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;

import java.util.List;

public interface PipeFieldCompiler<T extends IOInstance<T>, E extends Exception>{
	default List<IOField<T, ?>> compile(Struct<T> type, FieldSet<T> structFields) throws E{ return compile(type, structFields, false); }
	List<IOField<T, ?>> compile(Struct<T> type, FieldSet<T> structFields, boolean testRun) throws E;
}
