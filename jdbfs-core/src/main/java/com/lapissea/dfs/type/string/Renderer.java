package com.lapissea.dfs.type.string;

import java.util.ArrayList;
import java.util.List;

final class Renderer{
	
	private static final boolean FLAT  = true;
	private static final boolean BREAK = false;
	
	private record Cmd(int indent, boolean flat, Doc doc){ }
	
	static String render(Doc doc, int width, String indent){
		var out   = new StringBuilder();
		var stack = new ArrayList<Cmd>();
		stack.add(new Cmd(0, BREAK, doc));
		
		while(!stack.isEmpty()){
			var cmd = stack.removeLast();
			
			switch(cmd.doc){
				case Doc.Text(var value) -> out.append(value);
				case Doc.Empty ignored -> { }
				case Doc.Line(var soft) -> {
					if(cmd.flat){
						if(!soft) out.append(' ');
					}else{
						appendNewLine(out, indent, cmd.indent);
					}
				}
				case Doc.Concat(var parts) -> {
					for(int i = parts.size() - 1; i>=0; i--){
						stack.add(new Cmd(cmd.indent, cmd.flat, parts.get(i)));
					}
				}
				case Doc.Nest(var nested) -> stack.add(new Cmd(cmd.indent + 1, cmd.flat, nested));
				case Doc.Group(var grouped) -> {
					if(width<=0 || fits(width - column(out), grouped, stack)){
						stack.add(new Cmd(cmd.indent, FLAT, grouped));
					}else{
						stack.add(new Cmd(cmd.indent, BREAK, grouped));
					}
				}
			}
		}
		
		return out.toString();
	}
	
	private static void appendNewLine(StringBuilder out, String indent, int levels){
		out.append('\n');
		out.repeat(String.valueOf(indent), Math.max(0, levels));
	}
	
	private static int column(StringBuilder out){
		for(int i = out.length() - 1; i>=0; i--){
			if(out.charAt(i) == '\n') return out.length() - 1 - i;
		}
		return out.length();
	}
	
	private static boolean fits(int remaining, Doc doc, List<Cmd> rest){
		if(remaining<0) return false;
		
		var pending = new ArrayList<Cmd>();
		pending.add(new Cmd(0, FLAT, doc));
		
		for(var restIndex = rest.size() - 1; restIndex>=0 || !pending.isEmpty(); ){
			Cmd cmd;
			if(!pending.isEmpty()){
				cmd = pending.removeLast();
			}else{
				cmd = rest.get(restIndex--);
			}
			
			switch(cmd.doc){
				case Doc.Text(var value) -> {
					remaining -= value.length();
					if(remaining<0) return false;
				}
				case Doc.Empty ignored -> { }
				case Doc.Line(var soft) -> {
					if(!cmd.flat) return true;
					if(!soft){
						remaining--;
						if(remaining<0) return false;
					}
				}
				case Doc.Concat(var parts) -> {
					for(int i = parts.size() - 1; i>=0; i--){
						pending.add(new Cmd(cmd.indent, cmd.flat, parts.get(i)));
					}
				}
				case Doc.Nest(var nested) -> pending.add(new Cmd(cmd.indent + 1, cmd.flat, nested));
				case Doc.Group(var grouped) -> pending.add(new Cmd(cmd.indent, FLAT, grouped));
			}
		}
		
		return true;
	}
}
