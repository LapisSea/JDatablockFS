package com.lapissea.cfs.query;

import java.util.stream.Stream;

public sealed interface QueryCheck{
	
	interface SourceContain{
		Stream<QueryValueSource> sources();
		default Stream<QueryValueSource> deepSources(){
			return sources().flatMap(QueryValueSource::deep);
		}
	}
	
	default Stream<QueryCheck> deep(){
		return Stream.concat(Stream.of(this), innerValues());
	}
	default Stream<QueryCheck> innerValues(){
		return Stream.empty();
	}
	default QueryCheck negate(){
		return new Not(this);
	}
	
	record And(QueryCheck l, QueryCheck r) implements QueryCheck{
		@Override
		public String toString(){
			return "("+l+" && "+r+")";
		}
		@Override
		public Stream<QueryCheck> innerValues(){
			return Stream.concat(l.deep(), r.deep());
		}
	}
	
	record Or(QueryCheck l, QueryCheck r) implements QueryCheck{
		@Override
		public String toString(){
			return "("+l+" || "+r+")";
		}
		@Override
		public Stream<QueryCheck> innerValues(){
			return Stream.concat(l.deep(), r.deep());
		}
	}
	
	record Not(QueryCheck check) implements QueryCheck{
		@Override
		public String toString(){
			return "!("+check+")";
		}
		@Override
		public Stream<QueryCheck> innerValues(){
			return check.deep();
		}
		@Override
		public QueryCheck negate(){
			return check;
		}
	}
	
	record Equals(QueryValueSource l, QueryValueSource r) implements QueryCheck, SourceContain{
		@Override
		public String toString(){
			return l+" == "+r;
		}
		@Override
		public Stream<QueryValueSource> sources(){
			return Stream.of(l, r);
		}
	}
	
	record GreaterThan(QueryValueSource field, QueryValueSource arg) implements QueryCheck, SourceContain{
		@Override
		public String toString(){
			return field+" > "+arg;
		}
		@Override
		public Stream<QueryValueSource> sources(){
			return Stream.of(field, arg);
		}
	}
	
	record LessThan(QueryValueSource field, QueryValueSource arg) implements QueryCheck, SourceContain{
		@Override
		public String toString(){
			return field+" < "+arg;
		}
		@Override
		public Stream<QueryValueSource> sources(){
			return Stream.of(field, arg);
		}
	}
	
	record In(QueryValueSource needle, QueryValueSource hay) implements QueryCheck, SourceContain{
		@Override
		public String toString(){
			return "("+needle+" in "+hay+")";
		}
		@Override
		public Stream<QueryValueSource> sources(){
			return Stream.of(needle, hay);
		}
	}
}
