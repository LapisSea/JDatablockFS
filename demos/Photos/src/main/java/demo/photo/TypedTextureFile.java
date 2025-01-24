package demo.photo;

import com.lapissea.dfs.utils.iterableplus.Iters;

import java.io.File;
import java.util.List;

public record TypedTextureFile(TextureType type, File file) implements Comparable<TypedTextureFile>{
	public static List<TypedTextureFile> combine(List<TextureType> type, List<File> files){
		return Iters.zip(type, files, TypedTextureFile::new).toList();
	}
	@Override
	public int compareTo(TypedTextureFile o){
		var c = file.compareTo(o.file);
		return c != 0? c : this.type.compareTo(o.type);
	}
}
