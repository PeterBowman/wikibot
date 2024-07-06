package com.github.wikibot.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class CategoryTree {
    private final Node root;

    public CategoryTree(String rootData, int rootMembers) {
        root = new Node(null, rootData, rootMembers);
    }

    public Node getRoot() {
        return root;
    }

    @Override
    public String toString() {
        return root.toString();
    }

    public static class Node {
        private final Node parent;
        private final String name;
        private final int members;
        private final List<Node> children;

        private Node(Node parent, String name, int members) {
            this.parent = parent;
            this.name = Objects.requireNonNull(name);
            this.members = members;
            children = new ArrayList<>();
        }

        public Node getParent() {
            return parent;
        }

        public String getName() {
            return name;
        }

        public int getMembers() {
            return members;
        }

        public Node addChild(String name, int members) {
            var node = new Node(this, name, members);
            children.add(node);
            return node;
        }

        public Optional<Node> getChild(String name) {
            Objects.requireNonNull(name);

            return children.stream()
                .filter(n -> n.name.equals(name))
                .findFirst();
        }

        @Override
        public int hashCode() {
            return Objects.hash(parent, name);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            } else if (obj == null) {
                return false;
            }

            var other = (Node)obj;

            if (parent == null || other.parent == null) {
                return false; // can't be both null (two root nodes)
            }

            return parent.equals(other.parent) && name.equals(other.name);
        }

        @Override
        public String toString() {
            return String.format("%s (%d members, %d subcats)", name, members, children.size());
        }
    }
}
