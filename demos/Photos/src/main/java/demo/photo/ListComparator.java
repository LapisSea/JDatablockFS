package demo.photo;

import java.util.Comparator;
import java.util.List;

public class ListComparator<T extends Comparable<T>> implements Comparator<List<T>>{
	
	@Override
	public int compare(List<T> o1, List<T> o2){
		return compareLists(o1, o2);
	}
	
	public static <T extends Comparable<T>> int compareLists(List<T> o1, List<T> o2){
		for(int i = 0, j = Math.min(o1.size(), o2.size()); i<j; i++){
			int c = o1.get(i).compareTo(o2.get(i));
			if(c != 0){
				return c;
			}
		}
		return Integer.compare(o1.size(), o2.size());
	}
	
}
