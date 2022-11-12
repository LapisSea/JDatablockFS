package com.lapissea.cfs.query;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public interface Query<T>{
	
	Class<T> elementType();
	
	long count();
	
	Optional<T> any() throws IOException;
	
	default Query<T> filter(String expression, Object... args){
		var result=QueryExpressionParser.filter(elementType(), expression);
		var filter=result.filter();
		return filter(result.readFields(), o->filter.test(args, o));
	}
	
	Query<T> filter(Set<String> readFields, Predicate<T> filter);
}
