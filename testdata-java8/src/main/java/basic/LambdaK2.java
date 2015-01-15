package basic;

import java.util.*;

public class LambdaK2 {
    
    public static void main(String[] args) {
	run();
    }
    
    public static String run() {
	String[] array = {"5","4","3","2","1"};
	String asd = Arrays.asList(array).stream().reduce("", String::concat); 
	return asd;
    }
}
