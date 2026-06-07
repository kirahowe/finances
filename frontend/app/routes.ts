import { type RouteConfig, index, route } from "@react-router/dev/routes";

export default [
  index("routes/home.tsx"),
  route("setup", "routes/setup.tsx"),
  route("plaid-test", "routes/plaid-test.tsx"),
] satisfies RouteConfig;
