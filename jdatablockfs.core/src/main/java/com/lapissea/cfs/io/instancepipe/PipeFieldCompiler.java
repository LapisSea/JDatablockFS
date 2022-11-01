package com.lapissea.cfs.io.instancepipe;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.FieldSet;
import com.lapissea.cfs.type.field.IOField;

import java.util.List;

public interface PipeFieldCompiler<T extends IOInstance<T>, E extends Exception>{
	List<IOField<T, ?>> compile(Struct<T> type, FieldSet<T> structFields) throws E;
}
