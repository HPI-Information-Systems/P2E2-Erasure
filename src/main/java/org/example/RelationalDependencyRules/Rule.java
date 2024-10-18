package org.example.RelationalDependencyRules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.stream.Collectors;

public class Rule {
    public Attribute head;
    public ArrayList<Attribute> tail = new ArrayList<>(2);
    public ArrayList<String> tables = new ArrayList<>(1);
    public HashMap<String, String> table2Alias = new HashMap<>(2);
    public String condition;

    @Override
    public String toString() {
        return head.attribute + " <- " + tail.stream().map(a -> a.attribute).collect(Collectors.joining());
    }
}
