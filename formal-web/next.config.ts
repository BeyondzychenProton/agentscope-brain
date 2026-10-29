import type { NextConfig } from "next";

process.env.COPILOTKIT_TELEMETRY_DISABLED = "true";
process.env.NEXT_TELEMETRY_DISABLED = "1";

const config: NextConfig = {
  async rewrites() {
    return [
      { source: "/login", destination: "/" },
      { source: "/password/initial", destination: "/" },
      { source: "/workbench", destination: "/" },
    ];
  },
};
export default config;
