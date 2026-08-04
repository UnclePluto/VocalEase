import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { AdminApp } from "./AdminApp";
import "./styles.css";

const root = document.getElementById("root");

if (!root) {
  throw new Error("缺少应用挂载节点");
}

createRoot(root).render(
  <StrictMode>
    <AdminApp />
  </StrictMode>
);
