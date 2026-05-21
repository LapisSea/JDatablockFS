package com.lapissea.jorth.redo;

import com.lapissea.jorth.lang.ClassName;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

abstract class AnnotationContainer<SELF>{
	
	protected List<AnnotationDefinition> annotations = new ArrayList<>();
	
	public SELF annotation(AnnotationDefinition ann){
		annotations.add(ann);
		//noinspection unchecked
		return (SELF)this;
	}
	public SELF annotation(Class<? extends Annotation> ann){
		return annotation(new AnnotationDefinition(ClassName.of(ann)));
	}
	public SELF annotation(Class<? extends Annotation> ann, Consumer<AnnotationDefinition> init){
		var annV = new AnnotationDefinition(ClassName.of(ann));
		init.accept(annV);
		return annotation(annV);
	}
}
