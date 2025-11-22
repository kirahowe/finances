import { useState } from "react";
import type { Route } from "./+types/categories";
import { api, type Category } from "../lib/api";
import { useFetcher, useNavigation, useRevalidator } from "react-router";
import { LoadingIndicator } from "../components/LoadingIndicator";
import { generateCategoryIdent } from "../lib/identGenerator";

export function meta({}: Route.MetaArgs) {
  return [{ title: "Categories - Finance Aggregator" }];
}

export async function loader(): Promise<{ categories: Category[] }> {
  const categories = await api.getCategories();
  return { categories };
}

export async function action({ request }: Route.ActionArgs) {
  const formData = await request.formData();
  const intent = formData.get("intent");

  if (intent === "create") {
    const name = formData.get("name") as string;
    const type = formData.get("type") as "expense" | "income";
    const ident = formData.get("ident") as string | undefined;
    await api.createCategory({ name, type, ident: ident || undefined });
  } else if (intent === "update") {
    const id = parseInt(formData.get("id") as string);
    const name = formData.get("name") as string;
    const type = formData.get("type") as "expense" | "income";
    const ident = formData.get("ident") as string | undefined;
    await api.updateCategory(id, { name, type, ident: ident || undefined });
  } else if (intent === "delete") {
    const id = parseInt(formData.get("id") as string);
    await api.deleteCategory(id);
  }

  return { success: true };
}

export default function Categories({ loaderData }: Route.ComponentProps) {
  const { categories } = loaderData;
  const [showForm, setShowForm] = useState(false);
  const [editingCategory, setEditingCategory] = useState<Category | null>(null);
  const fetcher = useFetcher();
  const navigation = useNavigation();
  const revalidator = useRevalidator();

  const isLoading = navigation.state === 'loading';

  const handleEdit = (category: Category) => {
    setEditingCategory(category);
    setShowForm(true);
  };

  const handleDelete = (category: Category) => {
    if (confirm(`Delete category "${category["category/name"]}"?`)) {
      fetcher.submit(
        { intent: "delete", id: category["db/id"].toString() },
        { method: "post" }
      );
    }
  };

  const handleCloseForm = () => {
    setShowForm(false);
    setEditingCategory(null);
  };

  return (
    <div className="container">
      <h1>Categories</h1>

      <LoadingIndicator isLoading={isLoading} message="Loading categories..." />

      {!isLoading && (
        <div className="card">
          <div className="card-header">
            <h2>All Categories</h2>
            <div style={{ display: 'flex', gap: '10px' }}>
              <button className="button" onClick={() => setShowForm(true)}>
                Add Category
              </button>
              <button className="button button-secondary" onClick={() => revalidator.revalidate()}>
                Refresh
              </button>
            </div>
          </div>

          <table className="table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {categories.map((category) => (
              <tr key={category["db/id"]}>
                <td>{category["category/name"]}</td>
                <td>{category["category/type"]}</td>
                <td>
                  <div className="button-group">
                    <button
                      className="button button-secondary"
                      onClick={() => handleEdit(category)}
                    >
                      Edit
                    </button>
                    <button
                      className="button button-secondary"
                      onClick={() => handleDelete(category)}
                    >
                      Delete
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}

      {showForm && (
        <CategoryForm
          category={editingCategory}
          onClose={handleCloseForm}
        />
      )}

      <div className="nav-links">
        <a href="/" className="button button-secondary">
          Back to Home
        </a>
      </div>
    </div>
  );
}

function CategoryForm({
  category,
  onClose,
}: {
  category: Category | null;
  onClose: () => void;
}) {
  const fetcher = useFetcher();
  const isEditing = category !== null;
  const [name, setName] = useState(category?.["category/name"] || "");

  const generatedIdent = generateCategoryIdent(name);

  const handleSubmit = (e: React.FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    const formData = new FormData(e.currentTarget);
    // Add the auto-generated ident
    formData.set("ident", generatedIdent);
    fetcher.submit(formData, { method: "post" });
    onClose();
  };

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <h2>{isEditing ? "Edit Category" : "Add Category"}</h2>
        <form onSubmit={handleSubmit}>
          <input
            type="hidden"
            name="intent"
            value={isEditing ? "update" : "create"}
          />
          {isEditing && (
            <input
              type="hidden"
              name="id"
              value={category["db/id"].toString()}
            />
          )}

          <div className="form-group">
            <label className="form-label" htmlFor="name">
              Name
            </label>
            <input
              className="form-input"
              type="text"
              id="name"
              name="name"
              value={name}
              onChange={(e) => setName(e.target.value)}
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="type">
              Type
            </label>
            <select
              className="form-select"
              id="type"
              name="type"
              defaultValue={category?.["category/type"] || "expense"}
              required
            >
              <option value="expense">Expense</option>
              <option value="income">Income</option>
            </select>
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="ident">
              Identifier (Auto-generated)
            </label>
            <input
              className="form-input"
              type="text"
              id="ident"
              name="ident"
              value={generatedIdent}
              readOnly
              style={{ backgroundColor: '#f5f5f5', cursor: 'not-allowed' }}
            />
          </div>

          <div className="form-actions">
            <button type="button" className="button button-secondary" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="button">
              {isEditing ? "Update" : "Create"}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
