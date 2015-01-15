package basic;

import java.util.*;

public class LambdaK {
    
    public static void main(String[] args) {
	run();
    }
    
    public static String run() {
	Integer[] array = {1,2,3,4,5};
	String asd = Arrays.asList(array).stream().map(i -> ""+i).reduce("",String::concat); 
	return asd;
    }
}
