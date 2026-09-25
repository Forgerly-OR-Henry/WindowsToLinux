package probe;

import java.math.BigInteger;
import java.util.*;
import java.util.regex.Pattern;

public final class VersionRule {
    private static final Pattern VERSION=Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?");
    public record Version(List<BigInteger> core,List<String> pre) implements Comparable<Version> {
        @Override public int compareTo(Version right) {
            for(int i=0;i<3;i++){int compared=core.get(i).compareTo(right.core.get(i));if(compared!=0)return compared;}
            if(pre.isEmpty()!=right.pre.isEmpty())return pre.isEmpty()?1:-1;
            for(int i=0;i<Math.min(pre.size(),right.pre.size());i++){
                String a=pre.get(i),b=right.pre.get(i);boolean an=a.matches("[0-9]+"),bn=b.matches("[0-9]+");
                int compared=an&&bn?new BigInteger(a).compareTo(new BigInteger(b)):an!=bn?(an?-1:1):a.compareTo(b);if(compared!=0)return compared;
            }
            return Integer.compare(pre.size(),right.pre.size());
        }
    }
    public static Version parse(String text) {
        if(text==null||text.length()>512)throw new IllegalArgumentException("Invalid version");var matcher=VERSION.matcher(text);if(!matcher.matches())throw new IllegalArgumentException("Invalid version");
        var pre=matcher.group(4)==null?List.<String>of():List.of(matcher.group(4).split("\\."));
        for(var part:pre)if(part.matches("[0-9]+")&&part.length()>1&&part.startsWith("0"))throw new IllegalArgumentException("Leading zero");
        return new Version(List.of(new BigInteger(matcher.group(1)),new BigInteger(matcher.group(2)),new BigInteger(matcher.group(3))),pre);
    }
    public static boolean matches(String candidate,String range) {
        var version=parse(candidate);if(range==null||range.length()>512)throw new IllegalArgumentException("Invalid range");
        if(!range.startsWith("[")&&!range.startsWith("("))return version.compareTo(parse(range))==0;
        if(!range.endsWith("]")&&!range.endsWith(")"))throw new IllegalArgumentException("Invalid range");
        var halves=range.substring(1,range.length()-1).split(",",-1);if(halves.length!=2||halves[0].isEmpty()&&halves[1].isEmpty())throw new IllegalArgumentException("Invalid range");
        var lower=halves[0].isEmpty()?null:parse(halves[0]);var upper=halves[1].isEmpty()?null:parse(halves[1]);
        if(lower!=null&&upper!=null&&(lower.compareTo(upper)>0||lower.compareTo(upper)==0&&(!range.startsWith("[")||!range.endsWith("]"))))throw new IllegalArgumentException("Empty range");
        if(!version.pre.isEmpty()&&!((lower!=null&&!lower.pre.isEmpty()&&lower.core.equals(version.core))||(upper!=null&&!upper.pre.isEmpty()&&upper.core.equals(version.core))))return false;
        return (lower==null||version.compareTo(lower)>0||version.compareTo(lower)==0&&range.startsWith("["))&&(upper==null||version.compareTo(upper)<0||version.compareTo(upper)==0&&range.endsWith("]"));
    }
}
