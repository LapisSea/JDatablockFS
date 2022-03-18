package com.lapissea.cfs.chunk;

import com.lapissea.cfs.objects.ObjectID;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeLink;

import java.io.IOException;
import java.lang.reflect.Type;

public interface RootProvider{
	
	default <T extends IOInstance<T>> T request(Type genericType, ObjectID id) throws IOException{
		return request(TypeLink.of(genericType), id);
	}
	<T extends IOInstance<T>> T request(TypeLink genericType, ObjectID id) throws IOException;
	<T extends IOInstance<T>> void provide(T obj, ObjectID id) throws IOException;
	
	
}
