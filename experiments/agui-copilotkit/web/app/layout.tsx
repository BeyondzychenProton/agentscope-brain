import type { ReactNode } from "react";
import "@copilotkit/react-core/v2/styles.css";
import "./style.css";

export default function RootLayout({ children }: { children: ReactNode }) {
  return <html lang="zh-CN"><body>{children}</body></html>;
}
