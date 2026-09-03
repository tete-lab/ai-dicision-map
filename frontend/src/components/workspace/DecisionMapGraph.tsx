"use client";

import { useEffect, useMemo, useState } from "react";
import { Background, BackgroundVariant, Controls, Edge, Handle, Node, NodeProps, Position, ReactFlow } from "@xyflow/react";
import { CircleDot, Sparkles } from "lucide-react";
import type { DecisionResultResponse, DecisionSourceType } from "@/lib/api";

type DecisionResult = DecisionResultResponse["result"];
type Side = "left" | "right";
type RootData = { title: string; coverage: number };
type OptionData = { name: string; score: number | null; rank: number | null; encouraged: boolean; index: number; side: Side };
type CriterionData = { name: string; weight: number; sourceType: DecisionSourceType; score: number; side: Side };
type RootFlowNode = Node<RootData, "decisionRoot">;
type OptionFlowNode = Node<OptionData, "decisionOption">;
type CriterionFlowNode = Node<CriterionData, "decisionCriterion">;
type DecisionFlowNode = RootFlowNode | OptionFlowNode | CriterionFlowNode;

const sourceLabels: Record<DecisionSourceType, string> = {
  USER_FACT: "사용자 사실",
  USER_ASSUMPTION: "사용자 가정",
  AI_INFERENCE: "AI 추론",
};

function DecisionRootNode({ data }: NodeProps<RootFlowNode>) {
  return <div className="flow-root-node"><div className="flow-root-orbit" aria-hidden="true" /><span><Sparkles size={13} /> 나의 결정</span><strong>{data.title}</strong><small>구조 충족도 {data.coverage}%</small><Handle id="left" className="root-handle-left" type="source" position={Position.Bottom} /><Handle id="right" className="root-handle-right" type="source" position={Position.Bottom} /></div>;
}

function DecisionOptionNode({ data }: NodeProps<OptionFlowNode>) {
  return <div className={`flow-option-node tone-${data.index % 4} ${data.encouraged ? "encouraged" : ""}`}><Handle id="in" type="target" position={Position.Top} /><Handle id="out" type="source" position={Position.Bottom} />{data.encouraged && <em>응원 방향</em>}<div><span>{data.rank ? `${data.rank}순위` : "분석 중"}</span><strong>{data.name}</strong></div><b>{data.score ?? "-"}<small>점</small></b></div>;
}

function DecisionCriterionNode({ data }: NodeProps<CriterionFlowNode>) {
  return <div className="flow-criterion-node"><Handle type="target" position={Position.Top} /><span className="criterion-icon"><CircleDot size={15} /></span><div className="criterion-copy"><strong>{data.name}</strong><small>{sourceLabels[data.sourceType]} · {data.score}점</small></div><div className="criterion-weight"><strong>{Math.round(data.weight * 100)}%</strong><small>중요도</small></div></div>;
}

const nodeTypes = { decisionRoot: DecisionRootNode, decisionOption: DecisionOptionNode, decisionCriterion: DecisionCriterionNode };

function edgeTone(score: number) {
  if (score >= 70) return { stroke: "#1aa675", label: "#107553" };
  if (score < 45) return { stroke: "#e25f78", label: "#b43d55" };
  return { stroke: "#8290a5", label: "#536176" };
}

export function DecisionMapGraph({ result }: { result: DecisionResult }) {
  const [isMobile, setIsMobile] = useState(false);
  useEffect(() => {
    const query = window.matchMedia("(max-width: 767px)");
    const update = () => setIsMobile(query.matches);
    update(); query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);
  const { nodes, edges } = useMemo(() => {
    const centerX = 500;
    const rootY = 24;
    const optionsWithSide = result.options.map((option, index) => ({ option, index, side: (index % 2 === 0 ? "left" : "right") as Side }));
    const optionSeen = { left: 0, right: 0 };
    const generatedNodes: DecisionFlowNode[] = [
      { id: "root", type: "decisionRoot", position: { x: centerX - 76, y: rootY }, data: { title: result.title, coverage: result.completeness.weightedCoveragePercent } },
    ];
    const generatedEdges: Edge[] = [];

    optionsWithSide.forEach(({ option, index, side }) => {
      const sideIndex = optionSeen[side]++;
      const y = 170 + sideIndex * 135;
      const optionX = side === "left" ? 95 : 725;
      generatedNodes.push({ id: `option-${option.id}`, type: "decisionOption", position: { x: optionX, y }, data: { name: option.name, score: option.score, rank: option.rank, encouraged: option.id === result.guidance.encouragedOptionId, index, side } });
      generatedEdges.push({ id: `root-option-${option.id}`, source: "root", sourceHandle: side, target: `option-${option.id}`, targetHandle: "in", type: "bezier", animated: false, style: { stroke: option.id === result.guidance.encouragedOptionId ? "#6750d8" : "#aeb7c7", strokeWidth: option.id === result.guidance.encouragedOptionId ? 3 : 1.6 } });

      const optionAssessments = result.assessments.filter((assessment) => assessment.optionId === option.id);
      optionAssessments.forEach((assessment, criterionIndex) => {
        const criterion = result.criteria.find((item) => item.id === assessment.criterionId);
        if (!criterion) return;
        const criterionX = side === "left" ? 25 : 655;
        const spreadY = 310 + criterionIndex * 96 + sideIndex * 24;
        const nodeId = `criterion-${option.id}-${criterion.id}`;
        generatedNodes.push({ id: nodeId, type: "decisionCriterion", position: { x: criterionX, y: spreadY }, data: { name: criterion.name, weight: criterion.normalizedWeight, sourceType: criterion.sourceType, score: assessment.score, side } });
        const tone = edgeTone(assessment.score);
        generatedEdges.push({ id: `assessment-${option.id}-${criterion.id}`, source: `option-${option.id}`, sourceHandle: "out", target: nodeId, type: "bezier", label: `${assessment.score}점`, labelStyle: { fill: tone.label, fontSize: 11, fontWeight: 800 }, labelBgStyle: { fill: "#fff", fillOpacity: 0.96 }, labelBgPadding: [5, 3] as [number, number], labelBgBorderRadius: 7, style: { stroke: tone.stroke, strokeWidth: 1.5 + criterion.normalizedWeight * 3, opacity: 0.82 } });
      });
    });
    return { nodes: generatedNodes, edges: generatedEdges };
  }, [result]);

  return <div className="decision-flow" aria-label={`${result.title} 결정 지도`}><ReactFlow<DecisionFlowNode, Edge> nodes={nodes} edges={edges} nodeTypes={nodeTypes} fitView fitViewOptions={{ padding: 0.13, minZoom: 0.34, maxZoom: 0.92 }} minZoom={0.28} maxZoom={1.35} nodesDraggable={false} nodesConnectable={false} elementsSelectable={false} panOnDrag={isMobile} zoomOnScroll={isMobile} zoomOnPinch={isMobile} zoomOnDoubleClick={false} preventScrolling={isMobile} proOptions={{ hideAttribution: true }}><Background variant={BackgroundVariant.Dots} gap={24} size={1} color="#e2e5ec" />{isMobile && <Controls position="bottom-right" showInteractive={false} />}</ReactFlow></div>;
}
