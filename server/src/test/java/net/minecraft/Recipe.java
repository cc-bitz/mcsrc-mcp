package net.minecraft;

import java.util.List;

// Fixture for get_method_source: overloads, a constructor, an annotated override, braces inside
// strings, chars and comments that must not end a method early, an abstract member of an inner
// interface, and a method of an inner class.
public class Recipe {
    private final String id;

    public Recipe(String id) {
        this.id = id;
    }

    public String render() {
        return "{" + this.id + "}";
    }

    public String render(int width) {
        String open = "{{";
        char close = '}';
        // a lone } in a comment
        if (width > 0) {
            return open + this.id + close;
        }
        return render();
    }

    @Override
    public String toString() {
        return "Recipe[" + this.id + "]";
    }

    public interface Visitor {
        void visit(Recipe recipe);

        default int count(List<Recipe> recipes) {
            return recipes.size();
        }
    }

    public static class Builder {
        private String id = "";

        public Recipe build() {
            return new Recipe(this.id);
        }
    }
}
