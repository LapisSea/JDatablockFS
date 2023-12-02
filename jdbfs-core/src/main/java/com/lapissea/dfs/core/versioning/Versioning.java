package com.lapissea.dfs.core.versioning;

import com.lapissea.dfs.exceptions.IncompatibleVersionTransform;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.util.NotImplementedException;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class Versioning{
	
	public static final Versioning JUST_FAIL = new Versioning(Set.of(), List.of());
	
	private final Set<VersioningOptions>      options;
	private final List<VersionTransformer<?>> transformers;
	
	public Versioning(Set<VersioningOptions> options, List<VersionTransformer<?>> transformers){
		this.options = Collections.unmodifiableSet(
			options.isEmpty()? EnumSet.noneOf(VersioningOptions.class) : EnumSet.copyOf(options)
		);
		
		this.transformers = transformers;
	}
	
	
	public VersionTransformer<?> createTransformer(ClassVersionDiff diff){
		if(diff.changedFields().isEmpty() && diff.removedFields().isEmpty()){
			if(options.contains(VersioningOptions.ATTEMPT_NEW_VALUE_DEFAULT)){
				var nf     = diff.newFields();
				var struct = Struct.ofUnknown(diff.real());
				return new VersionTransformer<>(diff.real().getName(), unpacked -> {
					IOInstance inst = struct.make();
					for(String s : nf){
						IOField f = struct.getFields().byName(s).orElseThrow();
						if(!f.nullable() && f.isNull(null, inst)){
							throw new IncompatibleVersionTransform(
								"Can not automatically create new field: " + s +
								" because it is non null but is not initialized by default"
							);
						}
						
					}
					for(var e : unpacked){
						IOField f = struct.getFields().byName(e.getKey()).orElseThrow();
						f.set(null, inst, e.getValue());
					}
					return inst;
				});
			}
		}
		
		return new VersionTransformer<>(diff.real().getName(), unpacked -> {
			throw new NotImplementedException("diff:\n" + diff);//TODO
		});
	}
}
