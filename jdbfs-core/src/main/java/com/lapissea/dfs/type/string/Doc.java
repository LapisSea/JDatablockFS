package com.lapissea.dfs.type.string;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Wadler style document algebra used to build nested, width aware strings.
 * <a href="https://homepages.inf.ed.ac.uk/wadler/papers/prettier/prettier.pdf">Wadler writeup</a>
 * <p>
 * A tree is built independently of any width and only {@link #render} decides where groups may break
 */
public sealed interface Doc{
	
	Doc EMPTY = new Empty();
	
	record Empty() implements Doc{ }
	
	record Text(String value) implements Doc{ }
	
	record Line(boolean soft) implements Doc{ }
	
	record Concat(List<Doc> parts) implements Doc{ }
	
	record Nest(Doc doc) implements Doc{ }
	
	record Group(Doc doc) implements Doc{ }
	
	static Doc empty(){
		return EMPTY;
	}
	
	static Doc text(String value){
		return value.isEmpty()? EMPTY : new Text(value);
	}
	
	/**
	 * A space when flat, a newline when broken
	 */
	static Doc line(){
		return new Line(false);
	}
	
	/**
	 * Nothing when flat, a newline when broken
	 */
	static Doc softLine(){
		return new Line(true);
	}
	
	static Doc group(Doc doc){
		if(doc instanceof Group || doc instanceof Empty) return doc;
		return new Group(doc);
	}
	
	static Doc nest(Doc doc){
		return new Nest(doc);
	}
	
	static Doc concat(Doc... docs){
		var parts = new ArrayList<Doc>(docs.length);
		for(var doc : docs){
			if(doc == null || doc instanceof Empty) continue;
			parts.add(doc);
		}
		return switch(parts.size()){
			case 0 -> EMPTY;
			case 1 -> parts.getFirst();
			default -> new Concat(List.copyOf(parts));
		};
	}
	
	static Doc join(Doc separator, List<? extends Doc> docs){
		if(docs.isEmpty()) return EMPTY;
		if(docs.size() == 1) return docs.getFirst();
		
		var parts = new ArrayList<Doc>(docs.size()*2 - 1);
		for(int i = 0; i<docs.size(); i++){
			if(i != 0) parts.add(separator);
			parts.add(docs.get(i));
		}
		return new Concat(List.copyOf(parts));
	}
	
	default String render(int width){
		return render(width, "\t");
	}
	
	default String render(int width, String indent){
		return Renderer.render(this, width, indent);
	}
}
