package com.lapissea.fsf.collections.fixedlist;

import java.util.function.Function;

enum Action{
	ADD(s->s+1, t->"add("+t.element+")"),
	REMOVE(s->s-1, t->"remove("+t.index+")"),
	SET(s->s, t->"set("+t.element+" @"+t.index),
	CLEAR(s->0, t->"clear()");
	
	interface Resizer{
		int resize(int size);
	}
	
	final Resizer                          sizeModification;
	final Function<Transaction<?>, String> toString;
	
	Action(Resizer sizeModification, Function<Transaction<?>, String> toString){
		this.sizeModification=sizeModification;
		this.toString=toString;
	}
}
